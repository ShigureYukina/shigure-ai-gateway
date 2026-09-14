package com.nageoffer.shortlink.aigateway.observability;

import com.alibaba.fastjson2.JSON;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiGatewayMetricsRecorder {

    private static final String CALL_KEY_PREFIX = "short-link:ai-gateway:call:";

    private static final String METRIC_KEY_PREFIX = "short-link:ai-gateway:metric:model:";

    private static final String TENANT_METRIC_KEY_PREFIX = "short-link:ai-gateway:metric:tenant:";

    private static final String LATENCY_KEY_PREFIX = "short-link:ai-gateway:metric:latency:";

    private static final long METRIC_TTL_SECONDS = Duration.ofDays(2).toSeconds();

    private static final long CALL_TTL_SECONDS = Duration.ofDays(7).toSeconds();

    /**
     * 单次往返完成调用明细与聚合指标的写入。
     * <p>
     * 之所以把所有写入合并进一个 Lua 脚本，是因为该逻辑位于每请求的响应终态：
     * 逐条命令会产生十余次往返，一旦在事件循环上执行就会成为吞吐瓶颈。
     */
    private static final DefaultRedisScript<Long> CALL_RECORD_SCRIPT = new DefaultRedisScript<>("""
            redis.call('HINCRBY', KEYS[1], 'calls', 1)
            if tonumber(ARGV[1]) == 1 then redis.call('HINCRBY', KEYS[1], 'success', 1) end
            redis.call('HINCRBY', KEYS[1], 'tokenIn', tonumber(ARGV[2]))
            redis.call('HINCRBY', KEYS[1], 'tokenOut', tonumber(ARGV[3]))
            redis.call('HINCRBYFLOAT', KEYS[1], 'cost', ARGV[4])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))

            redis.call('HINCRBY', KEYS[2], 'calls', 1)
            if tonumber(ARGV[1]) == 1 then redis.call('HINCRBY', KEYS[2], 'success', 1) end
            if tonumber(ARGV[6]) == 1 then redis.call('HINCRBY', KEYS[2], 'cacheHit', 1) end
            redis.call('HINCRBY', KEYS[2], 'tokenIn', tonumber(ARGV[2]))
            redis.call('HINCRBY', KEYS[2], 'tokenOut', tonumber(ARGV[3]))
            redis.call('HINCRBYFLOAT', KEYS[2], 'cost', ARGV[4])
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[5]))

            redis.call('ZADD', KEYS[3], tonumber(ARGV[7]), ARGV[8])
            redis.call('EXPIRE', KEYS[3], tonumber(ARGV[5]))

            redis.call('RPUSH', KEYS[4], ARGV[9])
            redis.call('EXPIRE', KEYS[4], tonumber(ARGV[10]))
            return 1
            """, Long.class);

    /**
     * 租户维度缓存事件计数：同样合并为单次往返。
     */
    private static final DefaultRedisScript<Long> TENANT_CACHE_EVENT_SCRIPT = new DefaultRedisScript<>("""
            redis.call('HINCRBY', KEYS[1], ARGV[1], 1)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            return 1
            """, Long.class);

    private final ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    private final CostEstimator costEstimator;

    private final MeterRegistry meterRegistry;

    private final AiGatewayProperties properties;

    public void recordCall(AiCallRecord callRecord) {
        if (callRecord == null) {
            return;
        }
        if (callRecord.getCost() == null) {
            long tokenIn = callRecord.getTokenIn() == null ? 0L : callRecord.getTokenIn();
            long tokenOut = callRecord.getTokenOut() == null ? 0L : callRecord.getTokenOut();
            callRecord.setCost(costEstimator.estimate(callRecord.getModel(), tokenIn, tokenOut));
        }
        if (callRecord.getTimestamp() == null) {
            callRecord.setTimestamp(System.currentTimeMillis());
        }

        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String hour = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
        String callKey = CALL_KEY_PREFIX + day;
        String metricKey = METRIC_KEY_PREFIX + callRecord.getModel() + ":" + hour;
        String tenantMetricKey = TENANT_METRIC_KEY_PREFIX + safe(callRecord.getTenantId()) + ":" + hour;
        String latencyKey = LATENCY_KEY_PREFIX + callRecord.getModel() + ":" + hour;

        boolean success = callRecord.getStatus() != null && callRecord.getStatus() < 400;
        long tokenIn = callRecord.getTokenIn() == null ? 0L : callRecord.getTokenIn();
        long tokenOut = callRecord.getTokenOut() == null ? 0L : callRecord.getTokenOut();
        double cost = callRecord.getCost() == null ? 0D : callRecord.getCost();
        long latency = callRecord.getLatencyMillis() == null ? 0L : callRecord.getLatencyMillis();
        String latencyMember = callRecord.getRequestId() + ":" + System.currentTimeMillis();

        List<String> keys = List.of(metricKey, tenantMetricKey, latencyKey, callKey);
        List<String> args = List.of(
                success ? "1" : "0",
                String.valueOf(tokenIn),
                String.valueOf(tokenOut),
                String.valueOf(cost),
                String.valueOf(METRIC_TTL_SECONDS),
                Boolean.TRUE.equals(callRecord.getCacheHit()) ? "1" : "0",
                String.valueOf(latency),
                latencyMember,
                JSON.toJSONString(callRecord),
                String.valueOf(CALL_TTL_SECONDS));

        recordCallReactive(keys, args)
                .subscribe(ignored -> {
                }, ex -> log.warn("failed to record ai gateway call metrics: requestId={}", callRecord.getRequestId(), ex));

        recordPrometheusMetrics(callRecord);
        log.debug("ai_gateway_call record={}", callRecord);
    }

    /**
     * 暴露给测试的响应式写入路径：单次 EVAL 完成全部计数，不阻塞调用线程。
     */
    Flux<Long> recordCallReactive(List<String> keys, List<String> args) {
        return reactiveStringRedisTemplate.execute(CALL_RECORD_SCRIPT, keys, args);
    }

    public void recordTenantCacheEvent(String tenantId, String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return;
        }
        String hour = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
        String tenantMetricKey = TENANT_METRIC_KEY_PREFIX + safe(tenantId) + ":" + hour;
        String counterField = switch (eventType) {
            case "hit" -> "cacheHit";
            case "miss" -> "cacheMiss";
            case "write" -> "cacheWrite";
            default -> null;
        };
        if (counterField == null) {
            return;
        }

        recordTenantCacheEventReactive(tenantMetricKey, counterField)
                .subscribe(ignored -> {
                }, ex -> log.warn("failed to record tenant cache event metrics: tenantId={}", tenantId, ex));

        if (properties.getObservability().isTenantMetricsEnabled() && properties.getObservability().isCacheEventMetricsEnabled()) {
            meterRegistry.counter("ai_gateway_tenant_cache_events_total",
                    List.of(
                            Tag.of("tenant", safe(tenantId)),
                            Tag.of("event", counterField)
                    )).increment();
        }
    }

    Flux<Long> recordTenantCacheEventReactive(String tenantMetricKey, String counterField) {
        return reactiveStringRedisTemplate.execute(TENANT_CACHE_EVENT_SCRIPT,
                List.of(tenantMetricKey),
                List.of(counterField, String.valueOf(METRIC_TTL_SECONDS)));
    }

    public void recordTenantQuotaEvent(String tenantId, String appId, String provider, String model, String eventType, long tokens) {
        if (eventType == null || eventType.isBlank()) {
            return;
        }
        if (!properties.getObservability().isTenantMetricsEnabled() || !properties.getObservability().isQuotaEventMetricsEnabled()) {
            return;
        }
        meterRegistry.counter("ai_gateway_tenant_quota_events_total",
                List.of(
                        Tag.of("tenant", safe(tenantId)),
                        Tag.of("app", safe(appId)),
                        Tag.of("provider", safe(provider)),
                        Tag.of("model", safe(model)),
                        Tag.of("event", safe(eventType))
                )).increment();
        if (tokens > 0) {
            DistributionSummary.builder("ai_gateway_tenant_quota_tokens")
                    .tags(
                            "tenant", safe(tenantId),
                            "app", safe(appId),
                            "provider", safe(provider),
                            "model", safe(model),
                            "event", safe(eventType)
                    )
                    .register(meterRegistry)
                    .record(tokens);
        }
    }

    public Mono<ModelMetricsSnapshot> currentHourSnapshot(String model) {
        String hour = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
        String metricKey = METRIC_KEY_PREFIX + model + ":" + hour;
        String latencyKey = LATENCY_KEY_PREFIX + model + ":" + hour;

        Mono<Map<String, String>> metricHash = readHash(metricKey);
        Mono<Long> p95Latency = resolveP95Reactive(latencyKey);

        return Mono.zip(metricHash, p95Latency).map(tuple -> {
            Map<String, String> metrics = tuple.getT1();
            long callCount = toLong(metrics.get("calls"));
            long successCount = toLong(metrics.get("success"));
            double successRate = callCount == 0 ? 0D : successCount * 1.0D / callCount;
            return ModelMetricsSnapshot.builder()
                    .model(model)
                    .callCount(callCount)
                    .successRate(successRate)
                    .p95LatencyMillis(tuple.getT2())
                    .totalCost(toDouble(metrics.get("cost")))
                    .build();
        });
    }

    private Mono<Map<String, String>> readHash(String metricKey) {
        ReactiveHashOperations<String, String, String> hashOperations = reactiveStringRedisTemplate.opsForHash();
        return hashOperations.entries(metricKey)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private Mono<Long> resolveP95Reactive(String latencyKey) {
        ReactiveZSetOperations<String, String> zSetOperations = reactiveStringRedisTemplate.opsForZSet();
        return zSetOperations.size(latencyKey)
                .flatMap(total -> {
                    if (total == null || total <= 0) {
                        return Mono.just(0L);
                    }
                    long index = (long) Math.ceil(total * 0.95D) - 1;
                    if (index < 0) {
                        index = 0;
                    }
                    return zSetOperations.rangeWithScores(latencyKey, Range.closed(index, index))
                            .next()
                            .map(this::scoreToLong)
                            .defaultIfEmpty(0L);
                });
    }

    private Long scoreToLong(ZSetOperations.TypedTuple<String> tuple) {
        Double score = tuple.getScore();
        return score == null ? 0L : score.longValue();
    }

    private Long toLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private Double toDouble(String value) {
        if (value == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return 0D;
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private void recordPrometheusMetrics(AiCallRecord callRecord) {
        if (!properties.getObservability().isTenantMetricsEnabled()) {
            return;
        }
        String tenant = safe(callRecord.getTenantId());
        String app = safe(callRecord.getAppId());
        String provider = safe(callRecord.getProvider());
        String model = safe(callRecord.getModel());
        String statusClass = statusClass(callRecord.getStatus());
        String result = callRecord.getStatus() != null && callRecord.getStatus() < 400 ? "success" : "error";

        meterRegistry.counter("ai_gateway_tenant_requests_total",
                List.of(
                        Tag.of("tenant", tenant),
                        Tag.of("app", app),
                        Tag.of("provider", provider),
                        Tag.of("model", model),
                        Tag.of("result", result),
                        Tag.of("status_class", statusClass)
                )).increment();

        Timer.builder("ai_gateway_tenant_request_latency_ms")
                .tags(
                        "tenant", tenant,
                        "app", app,
                        "provider", provider,
                        "model", model,
                        "result", result
                )
                .register(meterRegistry)
                .record(callRecord.getLatencyMillis() == null ? 0L : callRecord.getLatencyMillis(), TimeUnit.MILLISECONDS);

        DistributionSummary.builder("ai_gateway_tenant_tokens_in")
                .tags("tenant", tenant, "app", app, "provider", provider, "model", model)
                .register(meterRegistry)
                .record(callRecord.getTokenIn() == null ? 0D : callRecord.getTokenIn());

        DistributionSummary.builder("ai_gateway_tenant_tokens_out")
                .tags("tenant", tenant, "app", app, "provider", provider, "model", model)
                .register(meterRegistry)
                .record(callRecord.getTokenOut() == null ? 0D : callRecord.getTokenOut());

        DistributionSummary.builder("ai_gateway_tenant_cost_usd")
                .tags("tenant", tenant, "app", app, "provider", provider, "model", model)
                .register(meterRegistry)
                .record(callRecord.getCost() == null ? 0D : callRecord.getCost());
    }

    private String statusClass(Integer status) {
        if (status == null || status < 100) {
            return "unknown";
        }
        return (status / 100) + "xx";
    }
}
