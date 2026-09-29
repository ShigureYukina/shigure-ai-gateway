package com.nageoffer.shortlink.aigateway.observability;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实 Redis 执行 {@link AiGatewayMetricsRecorder} 的内联 Lua 脚本。
 * <p>
 * 单测里 Redis 模板是 Mockito 桩，脚本体本身从未被执行过 —— 脚本写错、键拼错、
 * TTL 没设上，单测都会通过。这里把脚本真正交给 Redis 跑一遍。
 */
@Testcontainers(disabledWithoutDocker = true)
class AiGatewayMetricsRecorderRedisIntegrationTest {

    private static final String MODEL = "gpt-4o-mini";

    private static final String TENANT = "tenant-it";

    private static final int REDIS_PORT = 6379;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(REDIS_PORT)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    private static LettuceConnectionFactory connectionFactory;

    private static ReactiveStringRedisTemplate template;

    private AiGatewayMetricsRecorder recorder;

    @BeforeAll
    static void setUpRedis() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT));
        connectionFactory.afterPropertiesSet();
        template = new ReactiveStringRedisTemplate(connectionFactory);
    }

    @BeforeEach
    void setUp() {
        template.execute(connection -> connection.serverCommands().flushDb()).blockLast();

        AiGatewayProperties properties = new AiGatewayProperties();
        AiGatewayProperties.ModelPrice price = new AiGatewayProperties.ModelPrice();
        price.setInputPer1k(0.15D);
        price.setOutputPer1k(0.6D);
        properties.getObservability().getModelPrice().put(MODEL, price);

        recorder = new AiGatewayMetricsRecorder(template, new CostEstimator(properties), new SimpleMeterRegistry(), properties);
    }

    @Test
    void shouldAggregateCallsIntoRealRedis() {
        recorder.recordCall(successRecord("req-1", 100L, 40L, false));
        recorder.recordCall(successRecord("req-2", 200L, 10L, false));
        recorder.recordCall(successRecord("req-3", 300L, 50L, true));

        awaitCallListSize(3);

        Map<Object, Object> metric = hash(modelMetricKey());
        assertEquals("3", metric.get("calls"));
        assertEquals("3", metric.get("success"));
        assertEquals("600", metric.get("tokenIn"));
        assertEquals("100", metric.get("tokenOut"));
        assertTrue(Double.parseDouble((String) metric.get("cost")) > 0D, "cost 应由 ModelPrice 估算出来");

        assertEquals(3D, zsetSize(latencyKey()).doubleValue(), "每次调用都应写入一条延迟样本");
        assertEquals(3L, listSize(callKey()).longValue(), "调用明细应逐条落库");
    }

    @Test
    void shouldOnlyCountCacheHitWhenMarked() {
        recorder.recordCall(successRecord("req-miss", 10L, 5L, false));
        recorder.recordCall(successRecord("req-hit", 10L, 5L, true));

        awaitCallListSize(2);

        Map<Object, Object> tenantMetric = hash(tenantMetricKey());
        assertEquals("2", tenantMetric.get("calls"));
        assertEquals("1", tenantMetric.get("cacheHit"), "未命中缓存的调用不应计入 cacheHit");
        assertFalse(tenantMetric.containsKey("cacheMiss"), "脚本只维护 cacheHit，不该凭空写入其它字段");
    }

    @Test
    void shouldSetTtlOnEveryWrittenKey() {
        recorder.recordCall(successRecord("req-ttl", 10L, 5L, true));
        awaitCallListSize(1);
        recorder.recordTenantCacheEvent(TENANT, "hit");

        assertTtlWithin(modelMetricKey(), 2 * 24 * 3600L);
        assertTtlWithin(tenantMetricKey(), 2 * 24 * 3600L);
        assertTtlWithin(latencyKey(), 2 * 24 * 3600L);
        assertTtlWithin(callKey(), 7 * 24 * 3600L);
    }

    /**
     * 指标埋点必须保持"每请求一次脚本调用"：脚本内的命令数不在断言范围（它们不产生往返），
     * 断言的是脚本被调用的次数。若有人改回逐条命令写入，这里会立刻失败。
     */
    @Test
    void shouldExecuteScriptOncePerCall() {
        recorder.recordCall(successRecord("req-warm", 10L, 5L, false));
        awaitCallListSize(1);

        template.execute(connection -> connection.serverCommands().resetConfigStats()).blockLast();
        long before = scriptCalls();

        for (int i = 0; i < 5; i++) {
            recorder.recordCall(successRecord("req-" + i, 10L, 5L, false));
        }
        awaitCallListSize(6);

        assertEquals(before + 5, scriptCalls(), "5 次 recordCall 应只产生 5 次脚本调用（即 5 次 Redis 往返）");
    }

    private AiCallRecord successRecord(String requestId, long tokenIn, long tokenOut, boolean cacheHit) {
        return AiCallRecord.builder()
                .requestId(requestId)
                .provider("openai")
                .model(MODEL)
                .tenantId(TENANT)
                .appId("app-it")
                .keyId("key-it")
                .tokenIn(tokenIn)
                .tokenOut(tokenOut)
                .latencyMillis(35L)
                .status(200)
                .cacheHit(cacheHit)
                .build();
    }

    private String modelMetricKey() {
        return "short-link:ai-gateway:metric:model:" + MODEL + ":" + hour();
    }

    private String tenantMetricKey() {
        return "short-link:ai-gateway:metric:tenant:" + TENANT + ":" + hour();
    }

    private String latencyKey() {
        return "short-link:ai-gateway:metric:latency:" + MODEL + ":" + hour();
    }

    private String callKey() {
        return "short-link:ai-gateway:call:" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static String hour() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
    }

    private Map<Object, Object> hash(String key) {
        Map<Object, Object> entries = template.opsForHash().entries(key).collectMap(entry -> entry.getKey(), Map.Entry::getValue).block();
        assertNotNull(entries, "hash 不存在: " + key);
        return entries;
    }

    private Long zsetSize(String key) {
        return template.opsForZSet().size(key).block();
    }

    private Long listSize(String key) {
        return template.opsForList().size(key).block();
    }

    private long scriptCalls() {
        Properties stats = template.execute(connection -> connection.serverCommands().info("commandstats")).blockLast();
        assertNotNull(stats);
        long evalsha = commandCalls(stats, "cmdstat_evalsha");
        long eval = commandCalls(stats, "cmdstat_eval");
        return evalsha + eval;
    }

    private static long commandCalls(Properties stats, String key) {
        String value = stats.getProperty(key);
        if (value == null) {
            return 0L;
        }
        for (String part : value.split(",")) {
            if (part.startsWith("calls=")) {
                return Long.parseLong(part.substring("calls=".length()));
            }
        }
        return 0L;
    }

    private void assertTtlWithin(String key, long expectedSeconds) {
        Duration ttl = template.getExpire(key).block();
        assertNotNull(ttl, "key 不存在: " + key);
        long seconds = ttl.getSeconds();
        assertTrue(seconds > 0 && seconds <= expectedSeconds, "TTL 异常: key=" + key + " ttl=" + seconds);
    }

    /**
     * recordCall 是 fire-and-forget，测试用轮询等待写入生效，不引入额外等待时间。
     */
    private void awaitCallListSize(int expected) {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            Long size = listSize(callKey());
            if (size != null && size >= expected) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待指标写入时被中断", ex);
            }
        }
        assertEquals(expected, listSize(callKey()), "等待指标写入超时");
    }
}
