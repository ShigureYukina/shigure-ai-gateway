package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.adapter.ProviderAdapter;
import com.nageoffer.shortlink.aigateway.governance.ProviderRateLimitService;
import com.nageoffer.shortlink.aigateway.observability.AiRequestTraceBus;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTenantProperties;
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
import com.nageoffer.shortlink.aigateway.governance.RateLimitHeaderService;
import com.nageoffer.shortlink.aigateway.governance.UpstreamCredentialService;
import com.nageoffer.shortlink.aigateway.governance.UsageExtractor;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.observability.CostEstimator;
import com.nageoffer.shortlink.aigateway.observability.RequestTracePublisher;
import com.nageoffer.shortlink.aigateway.plugin.PluginChainService;
import com.nageoffer.shortlink.aigateway.routing.AiRoutingResult;
import com.nageoffer.shortlink.aigateway.routing.ProviderRoutingService;
import com.nageoffer.shortlink.aigateway.routing.RoutingPlanResolver;
import com.nageoffer.shortlink.aigateway.security.ApiKeyAuthService;
import com.nageoffer.shortlink.aigateway.service.AiGatewayService;
import com.nageoffer.shortlink.aigateway.service.UpstreamCallExecutor;
import com.nageoffer.shortlink.aigateway.tenant.TenantModelPolicyService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

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
        Mockito.when(responseCacheService.get(anyString())).thenReturn(Mono.just("{\"id\":\"global-cache\"}"));

        AiGatewayMetricsRecorder metricsRecorder = metricsRecorder(properties, new SimpleMeterRegistry());
        AiGatewayService service = gatewayService(properties, providerRoutingService, responseCacheService, Mockito.mock(RedisTokenQuotaService.class), metricsRecorder);
        ApiKeyAuthService authService = new ApiKeyAuthService(properties);

        WebTestClient client = WebTestClient.bindToController(new AiGatewayController(service, authService))
                .controllerAdvice(new AiGatewayExceptionHandler(new AiGatewayProperties()))
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
        Mockito.when(responseCacheService.get(anyString())).thenReturn(Mono.just("{\"id\":\"tenant-cache\"}"));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiGatewayMetricsRecorder metricsRecorder = metricsRecorder(properties, meterRegistry);
        RedisTokenQuotaService redisTokenQuotaService = Mockito.mock(RedisTokenQuotaService.class);
        AiGatewayService service = gatewayService(properties, providerRoutingService, responseCacheService, redisTokenQuotaService, metricsRecorder);
        ApiKeyAuthService authService = new ApiKeyAuthService(properties);

        WebTestClient client = WebTestClient.bindToController(new AiGatewayController(service, authService))
                .controllerAdvice(new AiGatewayExceptionHandler(new AiGatewayProperties()))
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

        ReactiveStringRedisTemplate redisTemplate = Mockito.mock(ReactiveStringRedisTemplate.class);
        // 预检脚本新契约：{allowed, minuteAfterReserve, dayAfterReserve}，拒绝路径同样回传三元素
        Mockito.when(redisTemplate.execute(ArgumentMatchers.<RedisScript<List>>any(), anyList(), anyList()))
                .thenReturn(Flux.just(List.of(0L, 0L, 0L)));

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
                .controllerAdvice(new AiGatewayExceptionHandler(new AiGatewayProperties()))
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
        RequestTracePublisher tracePublisher = new RequestTracePublisher(Mockito.mock(AiRequestTraceBus.class));
        UpstreamCallExecutor upstreamCallExecutor = new UpstreamCallExecutor(
                WebClient.builder().build(),
                List.<ProviderAdapter>of(),
                Mockito.mock(ReactiveCircuitBreakerFactory.class),
                Mockito.mock(UpstreamCredentialService.class),
                Mockito.mock(PluginChainService.class),
                Mockito.mock(AiSafetyGuard.class),
                new ProviderRateLimitService(properties),
                providerRoutingService,
                properties,
                tracePublisher
        );
        return new AiGatewayService(
                new RoutingPlanResolver(properties, providerRoutingService, new TenantModelPolicyService(properties)),
                metricsRecorder,
                quotaService,
                Mockito.mock(UsageExtractor.class),
                new AiCacheControlService(properties),
                new AiCacheKeyService(),
                new AiCacheStatsService(),
                responseCacheService,
                Mockito.mock(NoopSemanticCacheService.class),
                properties,
                Mockito.mock(AiGatewayTracer.class),
                new RateLimitHeaderService(),
                tracePublisher,
                upstreamCallExecutor
        );
    }

    private AiGatewayMetricsRecorder metricsRecorder(AiGatewayProperties properties, SimpleMeterRegistry meterRegistry) {
        ReactiveStringRedisTemplate redisTemplate = Mockito.mock(ReactiveStringRedisTemplate.class);
        Mockito.when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), anyList()))
                .thenReturn(Flux.just(1L));
        return new AiGatewayMetricsRecorder(redisTemplate, new CostEstimator(properties), meterRegistry, properties);
    }

    private AiGatewayProperties baseProperties() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getObservability().setTenantMetricsEnabled(true);
        properties.getObservability().setCacheEventMetricsEnabled(true);
        properties.getObservability().setQuotaEventMetricsEnabled(true);

        AiGatewayTenantProperties.TenantApiKeyCredential credential = new AiGatewayTenantProperties.TenantApiKeyCredential();
        credential.setApiKey("demo-platform-key");
        credential.setTenantId("demo-tenant");
        credential.setAppId("demo-app");
        credential.setKeyId("demo-key");
        properties.getTenant().getApiKeys().put("demo-key", credential);

        AiGatewayTenantProperties.TenantModelPolicy policy = new AiGatewayTenantProperties.TenantModelPolicy();
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
