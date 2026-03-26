package com.nageoffer.shortlink.aigateway.observability;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;

class AiGatewayMetricsRecorderTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldRecordTenantUsageAndEstimateCost() {
        AiGatewayProperties properties = new AiGatewayProperties();
        AiGatewayProperties.ModelPrice price = new AiGatewayProperties.ModelPrice();
        price.setInputPer1k(1D);
        price.setOutputPer1k(2D);
        properties.getObservability().getModelPrice().put("gpt-4o-mini", price);

        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ListOperations<String, String> listOperations = Mockito.mock(ListOperations.class);
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        ZSetOperations<String, String> zSetOperations = Mockito.mock(ZSetOperations.class);
        Mockito.when(redisTemplate.opsForList()).thenReturn(listOperations);
        Mockito.when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        Mockito.when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(redisTemplate, new CostEstimator(properties), meterRegistry, properties);
        AiCallRecord record = AiCallRecord.builder()
                .requestId("req-1")
                .provider("openai")
                .model("gpt-4o-mini")
                .tenantId("tenant-a")
                .appId("app-a")
                .keyId("key-a")
                .tokenIn(1000L)
                .tokenOut(500L)
                .latencyMillis(120L)
                .status(200)
                .cacheHit(true)
                .build();

        recorder.recordCall(record);

        Assertions.assertEquals(2D, record.getCost());
        Mockito.verify(hashOperations).increment(startsWith("short-link:ai-gateway:metric:tenant:tenant-a:"), eq("calls"), eq(1L));
        Mockito.verify(hashOperations).increment(startsWith("short-link:ai-gateway:metric:tenant:tenant-a:"), eq("cacheHit"), eq(1L));
        Mockito.verify(hashOperations).increment(startsWith("short-link:ai-gateway:metric:tenant:tenant-a:"), eq("cost"), eq(2D));
        Assertions.assertEquals(1D, meterRegistry.get("ai_gateway_tenant_requests_total")
                .tags("tenant", "tenant-a", "app", "app-a", "provider", "openai", "model", "gpt-4o-mini", "result", "success", "status_class", "2xx")
                .counter().count());
        Assertions.assertEquals(120D, meterRegistry.get("ai_gateway_tenant_request_latency_ms")
                .tags("tenant", "tenant-a", "app", "app-a", "provider", "openai", "model", "gpt-4o-mini", "result", "success")
                .timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));
        Assertions.assertEquals(2D, meterRegistry.get("ai_gateway_tenant_cost_usd")
                .tags("tenant", "tenant-a", "app", "app-a", "provider", "openai", "model", "gpt-4o-mini")
                .summary().totalAmount());
        Assertions.assertTrue(meterRegistry.get("ai_gateway_tenant_requests_total").meter().getId().getTags().stream()
                .noneMatch(tag -> "requestId".equals(tag.getKey())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnZeroCostWhenModelPriceMissing() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ListOperations<String, String> listOperations = Mockito.mock(ListOperations.class);
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        ZSetOperations<String, String> zSetOperations = Mockito.mock(ZSetOperations.class);
        Mockito.when(redisTemplate.opsForList()).thenReturn(listOperations);
        Mockito.when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        Mockito.when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AiGatewayProperties properties = new AiGatewayProperties();
        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(redisTemplate, new CostEstimator(properties), meterRegistry, properties);
        AiCallRecord record = AiCallRecord.builder()
                .requestId("req-2")
                .provider("openai")
                .model("unknown-model")
                .tenantId("tenant-a")
                .appId("app-a")
                .keyId("key-a")
                .tokenIn(100L)
                .tokenOut(100L)
                .latencyMillis(12L)
                .status(500)
                .build();

        recorder.recordCall(record);

        Assertions.assertEquals(0D, record.getCost());
        Mockito.verify(hashOperations, Mockito.never()).increment(startsWith("short-link:ai-gateway:metric:tenant:tenant-a:"), eq("success"), eq(1L));
        Assertions.assertEquals(1D, meterRegistry.get("ai_gateway_tenant_requests_total")
                .tags("tenant", "tenant-a", "app", "app-a", "provider", "openai", "model", "unknown-model", "result", "error", "status_class", "5xx")
                .counter().count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRecordTenantCacheEvents() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        Mockito.when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AiGatewayProperties properties = new AiGatewayProperties();
        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(redisTemplate, new CostEstimator(properties), meterRegistry, properties);

        recorder.recordTenantCacheEvent("tenant-a", "miss");
        recorder.recordTenantCacheEvent("tenant-a", "write");

        Mockito.verify(hashOperations).increment(startsWith("short-link:ai-gateway:metric:tenant:tenant-a:"), eq("cacheMiss"), eq(1L));
        Mockito.verify(hashOperations).increment(startsWith("short-link:ai-gateway:metric:tenant:tenant-a:"), eq("cacheWrite"), eq(1L));
        Assertions.assertEquals(1D, meterRegistry.get("ai_gateway_tenant_cache_events_total")
                .tags("tenant", "tenant-a", "event", "cacheMiss")
                .counter().count());
        Assertions.assertEquals(1D, meterRegistry.get("ai_gateway_tenant_cache_events_total")
                .tags("tenant", "tenant-a", "event", "cacheWrite")
                .counter().count());
    }

    @Test
    void shouldRecordTenantQuotaEvents() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiGatewayProperties properties = new AiGatewayProperties();
        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(Mockito.mock(StringRedisTemplate.class), new CostEstimator(properties), meterRegistry, properties);

        recorder.recordTenantQuotaEvent("tenant-a", "app-a", "openai", "gpt-4o-mini", "reserve", 128L);
        recorder.recordTenantQuotaEvent("tenant-a", "app-a", "openai", "gpt-4o-mini", "reject", 64L);

        Assertions.assertEquals(1D, meterRegistry.get("ai_gateway_tenant_quota_events_total")
                .tags("tenant", "tenant-a", "app", "app-a", "provider", "openai", "model", "gpt-4o-mini", "event", "reserve")
                .counter().count());
        Assertions.assertEquals(128D, meterRegistry.get("ai_gateway_tenant_quota_tokens")
                .tags("tenant", "tenant-a", "app", "app-a", "provider", "openai", "model", "gpt-4o-mini", "event", "reserve")
                .summary().totalAmount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSkipPrometheusCallMetricsWhenTenantMetricsDisabled() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ListOperations<String, String> listOperations = Mockito.mock(ListOperations.class);
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        ZSetOperations<String, String> zSetOperations = Mockito.mock(ZSetOperations.class);
        Mockito.when(redisTemplate.opsForList()).thenReturn(listOperations);
        Mockito.when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        Mockito.when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getObservability().setTenantMetricsEnabled(false);
        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(redisTemplate, new CostEstimator(properties), meterRegistry, properties);

        recorder.recordCall(AiCallRecord.builder()
                .requestId("req-disabled")
                .provider("openai")
                .model("gpt-4o-mini")
                .tenantId("tenant-a")
                .appId("app-a")
                .status(200)
                .latencyMillis(10L)
                .build());

        Mockito.verify(hashOperations).increment(startsWith("short-link:ai-gateway:metric:tenant:tenant-a:"), eq("calls"), eq(1L));
        Assertions.assertNull(meterRegistry.find("ai_gateway_tenant_requests_total").counter());
        Assertions.assertNull(meterRegistry.find("ai_gateway_tenant_request_latency_ms").timer());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSkipPrometheusCacheEventsWhenCacheMetricsDisabled() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        Mockito.when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getObservability().setCacheEventMetricsEnabled(false);
        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(redisTemplate, new CostEstimator(properties), meterRegistry, properties);

        recorder.recordTenantCacheEvent("tenant-a", "hit");

        Mockito.verify(hashOperations).increment(startsWith("short-link:ai-gateway:metric:tenant:tenant-a:"), eq("cacheHit"), eq(1L));
        Assertions.assertNull(meterRegistry.find("ai_gateway_tenant_cache_events_total").counter());
    }

    @Test
    void shouldSkipPrometheusQuotaEventsWhenQuotaMetricsDisabled() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getObservability().setQuotaEventMetricsEnabled(false);
        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(Mockito.mock(StringRedisTemplate.class), new CostEstimator(properties), meterRegistry, properties);

        recorder.recordTenantQuotaEvent("tenant-a", "app-a", "openai", "gpt-4o-mini", "reserve", 128L);

        Assertions.assertNull(meterRegistry.find("ai_gateway_tenant_quota_events_total").counter());
        Assertions.assertNull(meterRegistry.find("ai_gateway_tenant_quota_tokens").summary());
    }
}
