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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiGatewayMetricsRecorder {

    /**
     * 单次往返完成调用明细与聚合指标的写入。
     * <p>
     * 之所以把所有写入合并进一个 Lua 脚本，是因为该逻辑位于每请求的响应终态：
     * 逐条命令会产生十余次往返，一旦在事件循环上执行就会成为吞吐瓶颈。
     * <p>
     * 脚本里的 hash 字段名必须与 {@link AiGatewayMetricsKeys} 里的常量一致——健康分用同步命令读写同一个
     * hash，字段名漂移不会报错，只会让健康分读到 0。改字段名时同步改常量，并由
     * {@code AiGatewayMetricsKeysTest} 断言两者不脱节。
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
        // cost 缺省时补算：provider 健康分侧（ProviderHealthScoreService）会独立估一次同样的成本，
        // 两边写的是不同 Redis 键（那边是 provider:model 复合键，这里是 model 键），刻意不互相传值。
        if (callRecord.getCost() == null) {
            long tokenIn = callRecord.getTokenIn() == null ? 0L : callRecord.getTokenIn();
            long tokenOut = callRecord.getTokenOut() == null ? 0L : callRecord.getTokenOut();
            callRecord.setCost(costEstimator.estimate(callRecord.getModel(), tokenIn, tokenOut));
        }
        if (callRecord.getTimestamp() == null) {
            callRecord.setTimestamp(System.currentTimeMillis());
        }

        String hour = AiGatewayMetricsKeys.currentHour();
        String callKey = AiGatewayMetricsKeys.callKey(LocalDate.now());
        String metricKey = AiGatewayMetricsKeys.modelMetricKey(callRecord.getModel(), hour);
        String tenantMetricKey = AiGatewayMetricsKeys.tenantMetricKey(callRecord.getTenantId(), hour);
        String latencyKey = AiGatewayMetricsKeys.latencyKey(callRecord.getModel(), hour);

        boolean success = callRecord.getStatus() != null && callRecord.getStatus() < 400;
        long tokenIn = callRecord.getTokenIn() == null ? 0L : callRecord.getTokenIn();
        long tokenOut = callRecord.getTokenOut() == null ? 0L : callRecord.getTokenOut();
        double cost = callRecord.getCost() == null ? 0D : callRecord.getCost();
        long latency = callRecord.getLatencyMillis() == null ? 0L : callRecord.getLatencyMillis();
        String latencyMember = AiGatewayMetricsKeys.latencyMember(
                String.valueOf(callRecord.getRequestId()), System.currentTimeMillis());

        List<String> keys = List.of(metricKey, tenantMetricKey, latencyKey, callKey);
        List<String> args = List.of(
                success ? "1" : "0",
                String.valueOf(tokenIn),
                String.valueOf(tokenOut),
                String.valueOf(cost),
                String.valueOf(AiGatewayMetricsKeys.METRIC_TTL.toSeconds()),
                Boolean.TRUE.equals(callRecord.getCacheHit()) ? "1" : "0",
                String.valueOf(latency),
                latencyMember,
                JSON.toJSONString(callRecord),
                String.valueOf(AiGatewayMetricsKeys.CALL_TTL.toSeconds()));

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

    /**
     * 暴露给测试的脚本文本：用于断言脚本里的字段名与 {@link AiGatewayMetricsKeys} 常量一致。
     */
    static String callRecordScriptText() {
        return CALL_RECORD_SCRIPT.getScriptAsString();
    }

    public void recordTenantCacheEvent(String tenantId, String eventType) {
        String counterField = AiGatewayMetricsKeys.cacheEventField(eventType);
        if (counterField == null) {
            return;
        }
        String tenantMetricKey = AiGatewayMetricsKeys.tenantMetricKey(tenantId, AiGatewayMetricsKeys.currentHour());

        recordTenantCacheEventReactive(tenantMetricKey, counterField)
                .subscribe(ignored -> {
                }, ex -> log.warn("failed to record tenant cache event metrics: tenantId={}", tenantId, ex));

        if (properties.getObservability().isTenantMetricsEnabled() && properties.getObservability().isCacheEventMetricsEnabled()) {
            meterRegistry.counter("ai_gateway_tenant_cache_events_total",
                    List.of(
                            Tag.of("tenant", AiGatewayMetricsKeys.safe(tenantId)),
                            Tag.of("event", counterField)
                    )).increment();
        }
    }

    Flux<Long> recordTenantCacheEventReactive(String tenantMetricKey, String counterField) {
        return reactiveStringRedisTemplate.execute(TENANT_CACHE_EVENT_SCRIPT,
                List.of(tenantMetricKey),
                List.of(counterField, String.valueOf(AiGatewayMetricsKeys.METRIC_TTL.toSeconds())));
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
                        Tag.of("tenant", AiGatewayMetricsKeys.safe(tenantId)),
                        Tag.of("app", AiGatewayMetricsKeys.safe(appId)),
                        Tag.of("provider", AiGatewayMetricsKeys.safe(provider)),
                        Tag.of("model", AiGatewayMetricsKeys.safe(model)),
                        Tag.of("event", AiGatewayMetricsKeys.safe(eventType))
                )).increment();
        if (tokens > 0) {
            DistributionSummary.builder("ai_gateway_tenant_quota_tokens")
                    .tags(
                            "tenant", AiGatewayMetricsKeys.safe(tenantId),
                            "app", AiGatewayMetricsKeys.safe(appId),
                            "provider", AiGatewayMetricsKeys.safe(provider),
                            "model", AiGatewayMetricsKeys.safe(model),
                            "event", AiGatewayMetricsKeys.safe(eventType)
                    )
                    .register(meterRegistry)
                    .record(tokens);
        }
    }

    public Mono<ModelMetricsSnapshot> currentHourSnapshot(String model) {
        String hour = AiGatewayMetricsKeys.currentHour();
        String metricKey = AiGatewayMetricsKeys.modelMetricKey(model, hour);
        String latencyKey = AiGatewayMetricsKeys.latencyKey(model, hour);

        Mono<Map<String, String>> metricHash = readHash(metricKey);
        Mono<Long> p95Latency = resolveP95Reactive(latencyKey);

        return Mono.zip(metricHash, p95Latency).map(tuple -> {
            Map<String, String> metrics = tuple.getT1();
            long callCount = toLong(metrics.get(AiGatewayMetricsKeys.FIELD_CALLS));
            long successCount = toLong(metrics.get(AiGatewayMetricsKeys.FIELD_SUCCESS));
            double successRate = callCount == 0 ? 0D : successCount * 1.0D / callCount;
            return ModelMetricsSnapshot.builder()
                    .model(model)
                    .callCount(callCount)
                    .successRate(successRate)
                    .p95LatencyMillis(tuple.getT2())
                    .totalCost(toDouble(metrics.get(AiGatewayMetricsKeys.FIELD_COST)))
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

    private void recordPrometheusMetrics(AiCallRecord callRecord) {
        if (!properties.getObservability().isTenantMetricsEnabled()) {
            return;
        }
        String tenant = AiGatewayMetricsKeys.safe(callRecord.getTenantId());
        String app = AiGatewayMetricsKeys.safe(callRecord.getAppId());
        String provider = AiGatewayMetricsKeys.safe(callRecord.getProvider());
        String model = AiGatewayMetricsKeys.safe(callRecord.getModel());
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
