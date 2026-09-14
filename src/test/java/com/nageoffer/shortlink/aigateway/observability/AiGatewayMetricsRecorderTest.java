package com.nageoffer.shortlink.aigateway.observability;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.startsWith;

class AiGatewayMetricsRecorderTest {

    @SuppressWarnings("unchecked")
    private static ReactiveStringRedisTemplate mockTemplate() {
        ReactiveStringRedisTemplate template = Mockito.mock(ReactiveStringRedisTemplate.class);
        Mockito.when(template.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), anyList()))
                .thenReturn(Flux.just(1L));
        return template;
    }

    @SuppressWarnings("unchecked")
    private static List<String> capturedKeys(ReactiveStringRedisTemplate template, ArgumentCaptor<List<String>> keysCaptor,
                                             ArgumentCaptor<List<String>> argsCaptor) {
        Mockito.verify(template).execute(ArgumentMatchers.<RedisScript<Long>>any(), keysCaptor.capture(), argsCaptor.capture());
        return keysCaptor.getValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRecordTenantUsageAndEstimateCost() {
        AiGatewayProperties properties = new AiGatewayProperties();
        AiGatewayProperties.ModelPrice price = new AiGatewayProperties.ModelPrice();
        price.setInputPer1k(1D);
        price.setOutputPer1k(2D);
        properties.getObservability().getModelPrice().put("gpt-4o-mini", price);

        ReactiveStringRedisTemplate template = mockTemplate();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(template, new CostEstimator(properties), meterRegistry, properties);
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

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        List<String> keys = capturedKeys(template, keysCaptor, argsCaptor);
        List<String> args = argsCaptor.getValue();

        Assertions.assertEquals(4, keys.size());
        Assertions.assertTrue(keys.get(0).startsWith("short-link:ai-gateway:metric:model:gpt-4o-mini:"));
        Assertions.assertTrue(keys.get(1).startsWith("short-link:ai-gateway:metric:tenant:tenant-a:"));
        Assertions.assertTrue(keys.get(2).startsWith("short-link:ai-gateway:metric:latency:gpt-4o-mini:"));
        Assertions.assertTrue(keys.get(3).startsWith("short-link:ai-gateway:call:"));
        Assertions.assertEquals("1", args.get(0), "status<400 应记 success");
        Assertions.assertEquals("1000", args.get(1));
        Assertions.assertEquals("500", args.get(2));
        Assertions.assertEquals("2.0", args.get(3));
        Assertions.assertEquals("1", args.get(5), "cacheHit 应计数");
        Assertions.assertEquals("120", args.get(6));

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
        ReactiveStringRedisTemplate template = mockTemplate();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AiGatewayProperties properties = new AiGatewayProperties();
        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(template, new CostEstimator(properties), meterRegistry, properties);
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

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        capturedKeys(template, keysCaptor, argsCaptor);
        Assertions.assertEquals("0", argsCaptor.getValue().get(0), "status>=400 不应记 success");
        Assertions.assertEquals(1D, meterRegistry.get("ai_gateway_tenant_requests_total")
                .tags("tenant", "tenant-a", "app", "app-a", "provider", "openai", "model", "unknown-model", "result", "error", "status_class", "5xx")
                .counter().count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRecordTenantCacheEvents() {
        ReactiveStringRedisTemplate template = mockTemplate();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AiGatewayProperties properties = new AiGatewayProperties();
        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(template, new CostEstimator(properties), meterRegistry, properties);

        recorder.recordTenantCacheEvent("tenant-a", "miss");
        recorder.recordTenantCacheEvent("tenant-a", "write");

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(template, Mockito.times(2))
                .execute(ArgumentMatchers.<RedisScript<Long>>any(), keysCaptor.capture(), argsCaptor.capture());

        Assertions.assertEquals(1, keysCaptor.getAllValues().get(0).size());
        Assertions.assertTrue(keysCaptor.getAllValues().get(0).get(0).startsWith("short-link:ai-gateway:metric:tenant:tenant-a:"));
        Assertions.assertEquals("cacheMiss", argsCaptor.getAllValues().get(0).get(0));
        Assertions.assertEquals("cacheWrite", argsCaptor.getAllValues().get(1).get(0));

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
        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(mockTemplate(), new CostEstimator(properties), meterRegistry, properties);

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
        ReactiveStringRedisTemplate template = mockTemplate();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getObservability().setTenantMetricsEnabled(false);
        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(template, new CostEstimator(properties), meterRegistry, properties);

        recorder.recordCall(AiCallRecord.builder()
                .requestId("req-disabled")
                .provider("openai")
                .model("gpt-4o-mini")
                .tenantId("tenant-a")
                .appId("app-a")
                .status(200)
                .latencyMillis(10L)
                .build());

        Mockito.verify(template).execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), anyList());
        Assertions.assertNull(meterRegistry.find("ai_gateway_tenant_requests_total").counter());
        Assertions.assertNull(meterRegistry.find("ai_gateway_tenant_request_latency_ms").timer());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSkipPrometheusCacheEventsWhenCacheMetricsDisabled() {
        ReactiveStringRedisTemplate template = mockTemplate();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getObservability().setCacheEventMetricsEnabled(false);
        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(template, new CostEstimator(properties), meterRegistry, properties);

        recorder.recordTenantCacheEvent("tenant-a", "hit");

        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(template).execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), argsCaptor.capture());
        Assertions.assertEquals("cacheHit", argsCaptor.getValue().get(0));
        Assertions.assertNull(meterRegistry.find("ai_gateway_tenant_cache_events_total").counter());
    }

    @Test
    void shouldSkipPrometheusQuotaEventsWhenQuotaMetricsDisabled() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getObservability().setQuotaEventMetricsEnabled(false);
        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(mockTemplate(), new CostEstimator(properties), meterRegistry, properties);

        recorder.recordTenantQuotaEvent("tenant-a", "app-a", "openai", "gpt-4o-mini", "reserve", 128L);

        Assertions.assertNull(meterRegistry.find("ai_gateway_tenant_quota_events_total").counter());
        Assertions.assertNull(meterRegistry.find("ai_gateway_tenant_quota_tokens").summary());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReadCurrentHourSnapshotFromRedis() {
        ReactiveStringRedisTemplate template = Mockito.mock(ReactiveStringRedisTemplate.class);
        ReactiveHashOperations<String, String, String> hashOperations = Mockito.mock(ReactiveHashOperations.class);
        ReactiveZSetOperations<String, String> zSetOperations = Mockito.mock(ReactiveZSetOperations.class);
        Mockito.when(template.<String, String>opsForHash()).thenReturn(hashOperations);
        Mockito.when(template.opsForZSet()).thenReturn(zSetOperations);
        Mockito.when(hashOperations.entries(startsWith("short-link:ai-gateway:metric:model:")))
                .thenReturn(Flux.just(Map.entry("calls", "10"), Map.entry("success", "8"), Map.entry("cost", "1.5")));
        Mockito.when(zSetOperations.size(startsWith("short-link:ai-gateway:metric:latency:"))).thenReturn(Mono.just(10L));
        ZSetOperations.TypedTuple<String> tuple = Mockito.mock(ZSetOperations.TypedTuple.class);
        Mockito.when(tuple.getScore()).thenReturn(250D);
        Mockito.when(zSetOperations.rangeWithScores(startsWith("short-link:ai-gateway:metric:latency:"), ArgumentMatchers.<Range<Long>>any()))
                .thenReturn(Flux.just(tuple));

        AiGatewayProperties properties = new AiGatewayProperties();
        AiGatewayMetricsRecorder recorder = new AiGatewayMetricsRecorder(template, new CostEstimator(properties), new SimpleMeterRegistry(), properties);

        StepVerifier.create(recorder.currentHourSnapshot("gpt-4o-mini"))
                .assertNext(snapshot -> {
                    Assertions.assertEquals(10L, snapshot.getCallCount());
                    Assertions.assertEquals(0.8D, snapshot.getSuccessRate());
                    Assertions.assertEquals(250L, snapshot.getP95LatencyMillis());
                    Assertions.assertEquals(1.5D, snapshot.getTotalCost());
                })
                .verifyComplete();
    }
}
