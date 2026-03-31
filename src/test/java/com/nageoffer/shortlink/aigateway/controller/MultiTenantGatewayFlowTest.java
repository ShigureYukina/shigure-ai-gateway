package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.adapter.ProviderAdapter;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTracer;
import com.nageoffer.shortlink.aigateway.governance.AiCacheControlService;
import com.nageoffer.shortlink.aigateway.governance.AiCacheKeyService;
import com.nageoffer.shortlink.aigateway.governance.AiCacheStatsService;
import com.nageoffer.shortlink.aigateway.governance.AiSafetyGuard;
import com.nageoffer.shortlink.aigateway.governance.NoopSemanticCacheService;
import com.nageoffer.shortlink.aigateway.governance.QuotaKeyGenerator;
import com.nageoffer.shortlink.aigateway.governance.RedisResponseCacheService;
import com.nageoffer.shortlink.aigateway.governance.RedisTokenQuotaService;
import com.nageoffer.shortlink.aigateway.governance.TokenEstimator;
import com.nageoffer.shortlink.aigateway.governance.UsageExtractor;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.observability.CostEstimator;
import com.nageoffer.shortlink.aigateway.plugin.PluginChainService;
import com.nageoffer.shortlink.aigateway.routing.AiRoutingResult;
import com.nageoffer.shortlink.aigateway.routing.ProviderRoutingService;
import com.nageoffer.shortlink.aigateway.security.ApiKeyAuthService;
import com.nageoffer.shortlink.aigateway.service.AiGatewayService;
import com.nageoffer.shortlink.aigateway.tenant.TenantModelPolicyService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;

class MultiTenantGatewayFlowTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldFallbackToGlobalBehaviorWhenTenantDisabled() {
        AiGatewayProperties properties = baseProperties();
        properties.getTenant().setEnabled(false);
        properties.getCache().setEnabled(true);

        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        Mockito.when(providerRoutingService.resolve(anyString(), any())).thenReturn(AiRoutingResult.builder()
                .provider("openai")
                .providerModel("gpt-4o-mini")
                .upstreamUri("http://localhost")
                .build());

        RedisResponseCacheService responseCacheService = Mockito.mock(RedisResponseCacheService.class);
        Mockito.when(responseCacheService.get(anyString())).thenReturn(Optional.of("{\"id\":\"global-cache\"}"));

        AiGatewayMetricsRecorder metricsRecorder = metricsRecorder(properties, new SimpleMeterRegistry());
        AiGatewayService service = gatewayService(properties, providerRoutingService, responseCacheService, Mockito.mock(RedisTokenQuotaService.class), metricsRecorder);
        ApiKeyAuthService authService = new ApiKeyAuthService(properties);

        WebTestClient client = WebTestClient.bindToController(new AiGatewayController(service, authService))
                .controllerAdvice(new AiGatewayExceptionHandler())
                .build();

        client.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("gpt-4o-mini"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("global-cache");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldServeTenantCacheHitWithMappedModelAndMetrics() {
        AiGatewayProperties properties = baseProperties();
        properties.getTenant().setEnabled(true);
        properties.getCache().setEnabled(true);

        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        Mockito.when(providerRoutingService.resolve(anyString(), any())).thenReturn(AiRoutingResult.builder()
                .provider("openai")
                .providerModel("gpt-4o-mini")
                .upstreamUri("http://localhost")
                .build());

        RedisResponseCacheService responseCacheService = Mockito.mock(RedisResponseCacheService.class);
        Mockito.when(responseCacheService.get(anyString())).thenReturn(Optional.of("{\"id\":\"tenant-cache\"}"));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiGatewayMetricsRecorder metricsRecorder = metricsRecorder(properties, meterRegistry);
        RedisTokenQuotaService redisTokenQuotaService = Mockito.mock(RedisTokenQuotaService.class);
        AiGatewayService service = gatewayService(properties, providerRoutingService, responseCacheService, redisTokenQuotaService, metricsRecorder);
        ApiKeyAuthService authService = new ApiKeyAuthService(properties);

        WebTestClient client = WebTestClient.bindToController(new AiGatewayController(service, authService))
                .controllerAdvice(new AiGatewayExceptionHandler())
                .build();

        client.post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer demo-platform-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("default"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("tenant-cache");

        Mockito.verify(providerRoutingService).resolve(Mockito.eq("gpt-4o-mini-compatible"), any());
        Mockito.verifyNoInteractions(redisTokenQuotaService);
        Assertions.assertEquals(1D, meterRegistry.get("ai_gateway_tenant_requests_total")
                .tags("tenant", "demo-tenant", "app", "demo-app", "provider", "openai", "model", "gpt-4o-mini", "result", "success", "status_class", "2xx")
                .counter().count());
        Assertions.assertEquals(1D, meterRegistry.get("ai_gateway_tenant_cache_events_total")
                .tags("tenant", "demo-tenant", "event", "cacheHit")
                .counter().count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectQuotaAndEmitQuotaMetrics() {
        AiGatewayProperties properties = baseProperties();
        properties.getTenant().setEnabled(true);
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setMinTokenReserve(64L);

        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        Mockito.when(providerRoutingService.resolve(anyString(), any())).thenReturn(AiRoutingResult.builder()
                .provider("openai")
                .providerModel("gpt-4o-mini")
                .upstreamUri("http://localhost")
                .build());

        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiGatewayMetricsRecorder metricsRecorder = metricsRecorder(properties, meterRegistry);
        RedisTokenQuotaService quotaService = new RedisTokenQuotaService(
                redisTemplate,
                new TokenEstimator(properties),
                new QuotaKeyGenerator(properties),
                properties,
                metricsRecorder
        );
        AiGatewayService service = gatewayService(properties, providerRoutingService, Mockito.mock(RedisResponseCacheService.class), quotaService, metricsRecorder);
        ApiKeyAuthService authService = new ApiKeyAuthService(properties);

        WebTestClient client = WebTestClient.bindToController(new AiGatewayController(service, authService))
                .controllerAdvice(new AiGatewayExceptionHandler())
                .build();

        client.post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer demo-platform-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("default"))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.message").isEqualTo("Token 配额不足，已触发限流");

        Assertions.assertEquals(1D, meterRegistry.get("ai_gateway_tenant_quota_events_total")
                .tags("tenant", "demo-tenant", "app", "demo-app", "provider", "openai", "model", "gpt-4o-mini", "event", "reject")
                .counter().count());
    }

    private AiGatewayService gatewayService(AiGatewayProperties properties,
                                            ProviderRoutingService providerRoutingService,
                                            RedisResponseCacheService responseCacheService,
                                            RedisTokenQuotaService quotaService,
                                            AiGatewayMetricsRecorder metricsRecorder) {
        return new AiGatewayService(
                WebClient.builder().build(),
                providerRoutingService,
                new TenantModelPolicyService(properties),
                List.<ProviderAdapter>of(),
                metricsRecorder,
                Mockito.mock(AiSafetyGuard.class),
                quotaService,
                Mockito.mock(UsageExtractor.class),
                new AiCacheControlService(properties),
                new AiCacheKeyService(),
                new AiCacheStatsService(),
                responseCacheService,
                Mockito.mock(NoopSemanticCacheService.class),
                Mockito.mock(PluginChainService.class),
                properties,
                Mockito.mock(ReactiveCircuitBreakerFactory.class),
                Mockito.mock(AiGatewayTracer.class)
        );
    }

    private AiGatewayMetricsRecorder metricsRecorder(AiGatewayProperties properties, SimpleMeterRegistry meterRegistry) {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(redisTemplate.opsForList()).thenReturn(Mockito.mock(ListOperations.class));
        Mockito.when(redisTemplate.opsForHash()).thenReturn(Mockito.mock(HashOperations.class));
        Mockito.when(redisTemplate.opsForZSet()).thenReturn(Mockito.mock(ZSetOperations.class));
        return new AiGatewayMetricsRecorder(redisTemplate, new CostEstimator(properties), meterRegistry, properties);
    }

    private AiGatewayProperties baseProperties() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getObservability().setTenantMetricsEnabled(true);
        properties.getObservability().setCacheEventMetricsEnabled(true);
        properties.getObservability().setQuotaEventMetricsEnabled(true);

        AiGatewayProperties.TenantApiKeyCredential credential = new AiGatewayProperties.TenantApiKeyCredential();
        credential.setApiKey("demo-platform-key");
        credential.setTenantId("demo-tenant");
        credential.setAppId("demo-app");
        credential.setKeyId("demo-key");
        properties.getTenant().getApiKeys().put("demo-key", credential);

        AiGatewayProperties.TenantModelPolicy policy = new AiGatewayProperties.TenantModelPolicy();
        policy.getAllowedModels().add("gpt-4o-mini-compatible");
        policy.getModelMappings().put("default", "gpt-4o-mini-compatible");
        policy.setDefaultModelAlias("default");
        policy.setDefaultModel("gpt-4o-mini-compatible");
        properties.getTenant().getModelPolicies().put("demo-tenant", policy);
        return properties;
    }

    private AiChatCompletionReqDTO request(String model) {
        AiChatCompletionReqDTO request = new AiChatCompletionReqDTO();
        request.setModel(model);
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("user");
        message.setContent("hello");
        request.setMessages(List.of(message));
        request.setStream(false);
        return request;
    }
}
