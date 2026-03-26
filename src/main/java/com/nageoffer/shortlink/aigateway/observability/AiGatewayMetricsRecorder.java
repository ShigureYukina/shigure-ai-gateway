package com.nageoffer.shortlink.aigateway.observability;

import com.alibaba.fastjson2.JSON;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiGatewayMetricsRecorder {

    private static final String CALL_KEY_PREFIX = "short-link:ai-gateway:call:";

    private static final String METRIC_KEY_PREFIX = "short-link:ai-gateway:metric:model:";

    private static final String TENANT_METRIC_KEY_PREFIX = "short-link:ai-gateway:metric:tenant:";

    private static final String LATENCY_KEY_PREFIX = "short-link:ai-gateway:metric:latency:";

    private final StringRedisTemplate stringRedisTemplate;

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

        stringRedisTemplate.opsForList().rightPush(callKey, JSON.toJSONString(callRecord));
        stringRedisTemplate.expire(callKey, Duration.ofDays(7));

        stringRedisTemplate.opsForHash().increment(metricKey, "calls", 1);
        if (callRecord.getStatus() != null && callRecord.getStatus() < 400) {
            stringRedisTemplate.opsForHash().increment(metricKey, "success", 1);
        }
        stringRedisTemplate.opsForHash().increment(metricKey, "tokenIn", callRecord.getTokenIn() == null ? 0 : callRecord.getTokenIn());
        stringRedisTemplate.opsForHash().increment(metricKey, "tokenOut", callRecord.getTokenOut() == null ? 0 : callRecord.getTokenOut());
        stringRedisTemplate.opsForHash().increment(metricKey, "cost", callRecord.getCost() == null ? 0D : callRecord.getCost());
        stringRedisTemplate.expire(metricKey, Duration.ofDays(2));

        stringRedisTemplate.opsForHash().increment(tenantMetricKey, "calls", 1);
        if (callRecord.getStatus() != null && callRecord.getStatus() < 400) {
            stringRedisTemplate.opsForHash().increment(tenantMetricKey, "success", 1);
        }
        if (Boolean.TRUE.equals(callRecord.getCacheHit())) {
            stringRedisTemplate.opsForHash().increment(tenantMetricKey, "cacheHit", 1);
        }
        stringRedisTemplate.opsForHash().increment(tenantMetricKey, "tokenIn", callRecord.getTokenIn() == null ? 0 : callRecord.getTokenIn());
        stringRedisTemplate.opsForHash().increment(tenantMetricKey, "tokenOut", callRecord.getTokenOut() == null ? 0 : callRecord.getTokenOut());
        stringRedisTemplate.opsForHash().increment(tenantMetricKey, "cost", callRecord.getCost() == null ? 0D : callRecord.getCost());
        stringRedisTemplate.expire(tenantMetricKey, Duration.ofDays(2));

        String latencyMember = callRecord.getRequestId() + ":" + System.currentTimeMillis();
        stringRedisTemplate.opsForZSet().add(latencyKey, latencyMember, callRecord.getLatencyMillis() == null ? 0D : callRecord.getLatencyMillis());
        stringRedisTemplate.expire(latencyKey, Duration.ofDays(2));

        recordPrometheusMetrics(callRecord);

        log.info("ai_gateway_call record={}", callRecord);
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
        stringRedisTemplate.opsForHash().increment(tenantMetricKey, counterField, 1);
        stringRedisTemplate.expire(tenantMetricKey, Duration.ofDays(2));

        if (properties.getObservability().isTenantMetricsEnabled() && properties.getObservability().isCacheEventMetricsEnabled()) {
            meterRegistry.counter("ai_gateway_tenant_cache_events_total",
                    List.of(
                            Tag.of("tenant", safe(tenantId)),
                            Tag.of("event", counterField)
                    )).increment();
        }
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

    public ModelMetricsSnapshot currentHourSnapshot(String model) {
        String hour = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
        String metricKey = METRIC_KEY_PREFIX + model + ":" + hour;
        String latencyKey = LATENCY_KEY_PREFIX + model + ":" + hour;

        Long calls = toLong(stringRedisTemplate.opsForHash().get(metricKey, "calls"));
        Long success = toLong(stringRedisTemplate.opsForHash().get(metricKey, "success"));
        Double cost = toDouble(stringRedisTemplate.opsForHash().get(metricKey, "cost"));
        long callCount = calls == null ? 0L : calls;
        long successCount = success == null ? 0L : success;
        double successRate = callCount == 0 ? 0D : successCount * 1.0D / callCount;

        Long p95Latency = resolveP95(latencyKey);
        return ModelMetricsSnapshot.builder()
                .model(model)
                .callCount(callCount)
                .successRate(successRate)
                .p95LatencyMillis(p95Latency)
                .totalCost(cost == null ? 0D : cost)
                .build();
    }

    private Long resolveP95(String latencyKey) {
        Long total = stringRedisTemplate.opsForZSet().size(latencyKey);
        if (total == null || total <= 0) {
            return 0L;
        }
        long index = (long) Math.ceil(total * 0.95D) - 1;
        if (index < 0) {
            index = 0;
        }
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> tupleSet = stringRedisTemplate.opsForZSet().rangeWithScores(latencyKey, index, index);
        if (tupleSet == null || tupleSet.isEmpty()) {
            return 0L;
        }
        org.springframework.data.redis.core.ZSetOperations.TypedTuple<String> tuple = tupleSet.iterator().next();
        Double score = tuple.getScore();
        return score == null ? 0L : score.longValue();
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        return Long.parseLong(value.toString());
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        return Double.parseDouble(value.toString());
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
