package com.nageoffer.shortlink.aigateway.service;

import com.nageoffer.shortlink.aigateway.adapter.OpenAiCompatibleProviderAdapter;
import com.nageoffer.shortlink.aigateway.governance.ProviderRateLimitService;
import com.nageoffer.shortlink.aigateway.governance.ProviderKeyPoolService;
import com.nageoffer.shortlink.aigateway.observability.AiRequestTraceBus;
import com.nageoffer.shortlink.aigateway.adapter.ProviderAdapter;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTracer;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.governance.AiCacheControlService;
import com.nageoffer.shortlink.aigateway.governance.AiCacheKeyService;
import com.nageoffer.shortlink.aigateway.governance.AiCacheStatsService;
import com.nageoffer.shortlink.aigateway.governance.AiSafetyGuard;
import com.nageoffer.shortlink.aigateway.governance.QuotaPreCheckContext;
import com.nageoffer.shortlink.aigateway.governance.RateLimitHeaderService;
import com.nageoffer.shortlink.aigateway.governance.RedisResponseCacheService;
import com.nageoffer.shortlink.aigateway.governance.RedisTokenQuotaService;
import com.nageoffer.shortlink.aigateway.governance.SemanticCacheService;
import com.nageoffer.shortlink.aigateway.governance.UpstreamCredentialService;
import com.nageoffer.shortlink.aigateway.governance.UsageExtractor;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.observability.RequestTracePublisher;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import com.nageoffer.shortlink.aigateway.plugin.PluginChainService;
import com.nageoffer.shortlink.aigateway.plugin.PluginResponseContext;
import com.nageoffer.shortlink.aigateway.routing.AiRoutePolicy;
import com.nageoffer.shortlink.aigateway.routing.AiRoutingResult;
import com.nageoffer.shortlink.aigateway.routing.ProviderRoutingService;
import com.nageoffer.shortlink.aigateway.routing.RoutingPlanResolver;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import com.nageoffer.shortlink.aigateway.tenant.TenantModelPolicyService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 上游请求头契约。
 * <p>
 * 这组断言保护的是一条安全边界：客户端携带的 Authorization 是"平台身份"，
 * 只能用于网关入向鉴权，绝不能透传给上游；上游只接受配置化的凭证。
 */
class AiGatewayServiceUpstreamHeaderTest {

    private static final String CLIENT_PLATFORM_KEY = "Bearer client-platform-key";

    private AiGatewayProperties properties;

    private AtomicReference<HttpHeaders> capturedUpstreamHeaders;

    private AiGatewayService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new AiGatewayProperties();
        capturedUpstreamHeaders = new AtomicReference<>();

        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        Mockito.when(providerRoutingService.resolve(Mockito.anyString(), Mockito.any())).thenReturn(AiRoutingResult.builder()
                .provider("openai")
                .providerModel("gpt-4o-mini")
                .upstreamUri("http://upstream/v1/chat/completions")
                .routePolicy(AiRoutePolicy.builder()
                        .requestTimeout(Duration.ofSeconds(5))
                        .maxRetries(0)
                        .retryStatusCodes(Set.of())
                        .build())
                .build());
        TenantModelPolicyService tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        Mockito.when(tenantModelPolicyService.resolveModel(Mockito.any(), Mockito.anyString())).thenReturn("gpt-4o-mini");

        RedisTokenQuotaService quotaService = Mockito.mock(RedisTokenQuotaService.class);
        Mockito.when(quotaService.preCheck(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(Mono.just(QuotaPreCheckContext.builder().reservedTokens(0L).build()));
        Mockito.when(quotaService.release(Mockito.any())).thenReturn(Mono.empty());
        Mockito.when(quotaService.adjustByActualUsage(Mockito.any(), Mockito.anyLong())).thenReturn(Mono.empty());

        PluginChainService pluginChainService = Mockito.mock(PluginChainService.class);
        Mockito.when(pluginChainService.executeAfterResponse(Mockito.any()))
                .thenAnswer(invocation -> ((PluginResponseContext) invocation.getArgument(0)).getResponseBody());

        ReactiveCircuitBreakerFactory factory = Mockito.mock(ReactiveCircuitBreakerFactory.class);
        ReactiveCircuitBreaker circuitBreaker = Mockito.mock(ReactiveCircuitBreaker.class);
        Mockito.when(factory.create(Mockito.anyString())).thenReturn(circuitBreaker);
        Mockito.when(circuitBreaker.run(Mockito.any(Mono.class), Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

        UpstreamCredentialService credentialService =
                new UpstreamCredentialService(TenantConfigQueryService.fallbackOnly(properties), new ProviderKeyPoolService());
        RequestTracePublisher tracePublisher = new RequestTracePublisher(Mockito.mock(AiRequestTraceBus.class));
        UpstreamCallExecutor upstreamCallExecutor = new UpstreamCallExecutor(
                WebClient.builder().exchangeFunction(this::captureAndRespond).build(),
                List.of(new OpenAiCompatibleProviderAdapter()),
                factory,
                credentialService,
                pluginChainService,
                new AiSafetyGuard(properties),
                new ProviderRateLimitService(properties),
                providerRoutingService,
                properties,
                tracePublisher
        );
        service = new AiGatewayService(
                new RoutingPlanResolver(properties, providerRoutingService, tenantModelPolicyService),
                Mockito.mock(AiGatewayMetricsRecorder.class),
                quotaService,
                new UsageExtractor(),
                Mockito.mock(AiCacheControlService.class),
                new AiCacheKeyService(),
                new AiCacheStatsService(),
                Mockito.mock(RedisResponseCacheService.class),
                Mockito.mock(SemanticCacheService.class),
                properties,
                Mockito.mock(AiGatewayTracer.class),
                new RateLimitHeaderService(),
                tracePublisher,
                upstreamCallExecutor
        );
    }

    @Test
    void shouldReplaceClientAuthorizationWithConfiguredUpstreamCredential() {
        platformCredential("openai", "sk-upstream-secret");

        String body = service.chatCompletion(request(), clientHeaders(), tenantContext()).block();

        Assertions.assertNotNull(body);
        Assertions.assertEquals("Bearer sk-upstream-secret", capturedUpstreamHeaders.get().getFirst(HttpHeaders.AUTHORIZATION));
        Assertions.assertNotEquals(CLIENT_PLATFORM_KEY, capturedUpstreamHeaders.get().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void shouldUseTenantByokCredentialForUpstream() {
        platformCredential("openai", "sk-platform");
        AiGatewayProperties.ProviderCredential byok = new AiGatewayProperties.ProviderCredential();
        byok.setApiKey("sk-tenant-byok");
        properties.getTenant().getProviderCredentials()
                .computeIfAbsent("tenant-a", key -> new java.util.HashMap<>())
                .put("openai", byok);

        service.chatCompletion(request(), clientHeaders(), tenantContext()).block();

        Assertions.assertEquals("Bearer sk-tenant-byok", capturedUpstreamHeaders.get().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void shouldForwardWhitelistedClientHeadersAndRequestId() {
        platformCredential("openai", "sk-platform");

        service.chatCompletion(request(), clientHeaders(), tenantContext()).block();

        Assertions.assertEquals("req-fixed-1", capturedUpstreamHeaders.get().getFirst("X-Request-Id"));
        Assertions.assertEquals("assistants=v2", capturedUpstreamHeaders.get().getFirst("OpenAI-Beta"));
        // 未在白名单内的客户端头不应被搬运
        Assertions.assertNull(capturedUpstreamHeaders.get().getFirst("X-Internal-Trace"));
    }

    @Test
    void shouldFailFastWhenNoUpstreamCredentialConfigured() {
        RuntimeException exception = Assertions.assertThrows(RuntimeException.class,
                () -> service.chatCompletion(request(), clientHeaders(), tenantContext()).block());

        Assertions.assertNotNull(exception);
        Assertions.assertTrue(String.valueOf(exception.getMessage()).contains("未配置上游凭证"),
                "缺凭证时必须快速失败并给出可行动的错误: " + exception.getMessage());
    }

    @Test
    void shouldApplyProviderExtraHeadersFromCredential() {
        AiGatewayProperties.ProviderCredential credential = platformCredential("openai", "sk-platform");
        credential.setExtraHeaders(java.util.Map.of("anthropic-version", "2023-06-01"));

        service.chatCompletion(request(), clientHeaders(), tenantContext()).block();

        Assertions.assertEquals("2023-06-01", capturedUpstreamHeaders.get().getFirst("anthropic-version"));
    }

    private Mono<ClientResponse> captureAndRespond(org.springframework.web.reactive.function.client.ClientRequest request) {
        capturedUpstreamHeaders.set(request.headers());
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"id\":\"chatcmpl-1\",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                .build());
    }

    private HttpHeaders clientHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, CLIENT_PLATFORM_KEY);
        headers.set("X-Request-Id", "req-fixed-1");
        headers.set("OpenAI-Beta", "assistants=v2");
        headers.set("X-Internal-Trace", "should-not-be-forwarded");
        return headers;
    }

    private TenantContext tenantContext() {
        return new TenantContext("tenant-a", "app-a", "key-a");
    }

    private AiGatewayProperties.ProviderCredential platformCredential(String provider, String apiKey) {
        AiGatewayProperties.ProviderCredential credential = new AiGatewayProperties.ProviderCredential();
        credential.setApiKey(apiKey);
        properties.getUpstream().getProviderCredentials().put(provider, credential);
        return credential;
    }

    private AiChatCompletionReqDTO request() {
        AiChatCompletionReqDTO request = new AiChatCompletionReqDTO();
        request.setModel("gpt-4o-mini");
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("user");
        message.setContent("hello");
        request.setMessages(List.of(message));
        request.setStream(false);
        return request;
    }
}
