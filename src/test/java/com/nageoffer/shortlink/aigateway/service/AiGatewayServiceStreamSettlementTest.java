package com.nageoffer.shortlink.aigateway.service;

import com.nageoffer.shortlink.aigateway.adapter.ProviderAdapter;
import com.nageoffer.shortlink.aigateway.governance.ProviderRateLimitService;
import com.nageoffer.shortlink.aigateway.governance.ProviderKeyPoolService;
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
import com.nageoffer.shortlink.aigateway.observability.AiRequestTraceBus;
import com.nageoffer.shortlink.aigateway.observability.AiRequestTraceEvent;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流式链路的用量结算与配额兜底。
 * <p>
 * 流式响应没有"整体响应体"可供事后解析，且客户端随时可能断开，
 * 因此这组断言覆盖三种终态：正常完成按实际用量结算、失败退还预扣、取消退还预扣。
 */
class AiGatewayServiceStreamSettlementTest {

    private static final String REQUEST_ID = "req-stream-1";

    private AiGatewayProperties properties;

    private RedisTokenQuotaService quotaService;

    private RateLimitHeaderService rateLimitHeaderService;

    private ProviderAdapter providerAdapter;

    private ProviderRoutingService providerRoutingService;

    private TenantModelPolicyService tenantModelPolicyService;

    private PluginChainService pluginChainService;

    private ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory;

    private AiRequestTraceBus requestTraceBus;

    private AiGatewayService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new AiGatewayProperties();
        properties.getUpstream().getProviderCredentials().put("openai", credential("sk-platform"));
        properties.getUpstream().getProviderCredentials().put("claude", credential("sk-platform-claude"));
        quotaService = Mockito.mock(RedisTokenQuotaService.class);
        rateLimitHeaderService = new RateLimitHeaderService();
        providerAdapter = Mockito.mock(ProviderAdapter.class);

        providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        Mockito.when(providerRoutingService.resolve(Mockito.anyString(), Mockito.any())).thenReturn(AiRoutingResult.builder()
                .provider("openai")
                .providerModel("gpt-4o-mini")
                .upstreamUri("http://upstream/v1/chat/completions")
                .routePolicy(routePolicy())
                .build());
        tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        Mockito.when(tenantModelPolicyService.resolveModel(Mockito.any(), Mockito.anyString())).thenReturn("gpt-4o-mini");

        Mockito.when(providerAdapter.providerName()).thenReturn("openai");
        Mockito.when(providerAdapter.toUpstreamRequest(Mockito.any())).thenReturn(Map.of("model", "gpt-4o-mini"));

        pluginChainService = Mockito.mock(PluginChainService.class);
        Mockito.when(pluginChainService.executeAfterResponse(Mockito.any()))
                .thenAnswer(invocation -> ((PluginResponseContext) invocation.getArgument(0)).getResponseBody());

        requestTraceBus = Mockito.mock(AiRequestTraceBus.class);
        circuitBreakerFactory = Mockito.mock(ReactiveCircuitBreakerFactory.class);
        ReactiveCircuitBreaker circuitBreaker = Mockito.mock(ReactiveCircuitBreaker.class);
        Mockito.when(circuitBreakerFactory.create(Mockito.anyString())).thenReturn(circuitBreaker);
        Mockito.when(circuitBreaker.run(Mockito.any(Flux.class), Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = buildService(sseWebClient(), List.of(providerAdapter));
    }

    /**
     * 复用同一套装配，便于替换上游 WebClient 与适配器列表（例如验证回退链路）。
     */
    private AiGatewayService buildService(WebClient webClient, List<ProviderAdapter> adapters) {
        RequestTracePublisher tracePublisher = new RequestTracePublisher(requestTraceBus);
        UpstreamCallExecutor upstreamCallExecutor = new UpstreamCallExecutor(
                webClient,
                adapters,
                circuitBreakerFactory,
                new UpstreamCredentialService(TenantConfigQueryService.fallbackOnly(properties), new ProviderKeyPoolService()),
                pluginChainService,
                new AiSafetyGuard(properties),
                new ProviderRateLimitService(properties),
                providerRoutingService,
                properties,
                tracePublisher
        );
        return new AiGatewayService(
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
                rateLimitHeaderService,
                tracePublisher,
                upstreamCallExecutor
        );
    }

    private WebClient sseWebClient() {
        return WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                .body("")
                .build())).build();
    }

    private AiRoutePolicy routePolicy() {
        return AiRoutePolicy.builder()
                .requestTimeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .retryStatusCodes(Set.of())
                .build();
    }

    /**
     * 主通道返回 500、回退通道返回 SSE，用于验证回退后的归属。
     */
    private WebClient routeAwareWebClient() {
        return WebClient.builder().exchangeFunction(request -> Mono.just(request.url().toString().contains("/v1/messages")
                ? ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                        .body("")
                        .build()
                : ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("{\"error\":\"upstream down\"}")
                        .build())).build();
    }

    @Test
    void shouldSettleQuotaWithActualUsageAndExposeRemainingHeaders() {
        stubQuotaPreCheck(100L, 1000L);
        Mockito.when(quotaService.adjustByActualUsage(Mockito.any(), Mockito.eq(15L)))
                .thenReturn(Mono.just(new RedisTokenQuotaService.QuotaSettleResult(15L, 15L, 15L)));
        Mockito.when(providerAdapter.fromUpstreamSse(Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(
                        "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                        "{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}",
                        "[DONE]"));

        List<String> chunks = service.streamChatCompletion(request(), httpRequest(), tenantContext()).collectList().block();

        Assertions.assertNotNull(chunks);
        Assertions.assertEquals(3, chunks.size());
        Mockito.verify(quotaService).adjustByActualUsage(Mockito.any(), Mockito.eq(15L));
        Mockito.verify(quotaService, Mockito.never()).release(Mockito.any());
        // 正常完成把真实用量一并反馈给健康分，供动态路由排名
        Mockito.verify(providerRoutingService).recordProviderOutcome(Mockito.eq("openai"), Mockito.eq("gpt-4o-mini"),
                Mockito.anyLong(), Mockito.eq(true), Mockito.eq(10L), Mockito.eq(5L));

        // 流式链路的埋点要能画出"到首字"和"收尾"两段
        ArgumentCaptor<AiRequestTraceEvent> traceCaptor = ArgumentCaptor.forClass(AiRequestTraceEvent.class);
        Mockito.verify(requestTraceBus, Mockito.atLeastOnce()).publish(traceCaptor.capture());
        List<String> stages = traceCaptor.getAllValues().stream().map(AiRequestTraceEvent::getStage).distinct().toList();
        Assertions.assertTrue(stages.containsAll(List.of("routing", "quota", "upstream", "first-token", "completed")),
                "缺少阶段: " + stages);

        // 限流响应头反映的是预检（预扣后）时刻的用量快照——结算发生在响应提交之后，
        // 头里永远带不上结算后的值，这是设计而不是缺陷
        Map<String, String> rateLimitHeaders = rateLimitHeaderService.consume(REQUEST_ID);
        Assertions.assertEquals("100", rateLimitHeaders.get("x-ratelimit-limit-tokens"));
        Assertions.assertEquals("85", rateLimitHeaders.get("x-ratelimit-remaining-tokens"));
    }

    @Test
    void shouldAppendDoneWhenUpstreamOmitsTerminator() {
        stubQuotaPreCheck(100L, 1000L);
        Mockito.when(providerAdapter.fromUpstreamSse(Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"));

        List<String> chunks = service.streamChatCompletion(request(), httpRequest(), tenantContext()).collectList().block();

        Assertions.assertNotNull(chunks);
        Assertions.assertEquals(2, chunks.size());
        Assertions.assertEquals("[DONE]", chunks.get(1));
    }

    @Test
    void shouldReleaseReservedQuotaWhenStreamFails() {
        stubQuotaPreCheck(100L, 1000L);
        Mockito.when(quotaService.release(Mockito.any())).thenReturn(Mono.empty());
        Mockito.when(providerAdapter.fromUpstreamSse(Mockito.any(), Mockito.any()))
                .thenReturn(Flux.error(new IllegalStateException("upstream broken")));

        List<String> chunks = service.streamChatCompletion(request(), httpRequest(), tenantContext()).collectList().block();

        // 失败不再硬断流，而是下发错误帧 + [DONE]
        Assertions.assertNotNull(chunks);
        Assertions.assertEquals(2, chunks.size());
        Assertions.assertTrue(chunks.get(0).contains("\"error\""));
        Assertions.assertEquals("[DONE]", chunks.get(1));
        Mockito.verify(quotaService).release(Mockito.any());
        Mockito.verify(quotaService, Mockito.never()).adjustByActualUsage(Mockito.any(), Mockito.anyLong());
        // 流中断归因到通道自身，上报一次失败
        Mockito.verify(providerRoutingService).recordProviderOutcome(Mockito.eq("openai"), Mockito.eq("gpt-4o-mini"),
                Mockito.anyLong(), Mockito.eq(false), Mockito.eq(0L), Mockito.eq(0L));
    }

    @Test
    void shouldReleaseReservedQuotaWhenClientCancels() {
        stubQuotaPreCheck(100L, 1000L);
        Mockito.when(quotaService.release(Mockito.any())).thenReturn(Mono.empty());
        Mockito.when(providerAdapter.fromUpstreamSse(Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}")
                        .concatWith(Flux.never()));

        StepVerifier.create(service.streamChatCompletion(request(), httpRequest(), tenantContext()))
                .expectNextMatches(chunk -> chunk.contains("hi"))
                .thenCancel()
                .verify();

        Mockito.verify(quotaService).release(Mockito.any());
        // 取消是客户端行为，不能算到 provider 头上
        Mockito.verify(providerRoutingService, Mockito.never()).recordProviderOutcome(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyBoolean(),
                Mockito.anyLong(), Mockito.anyLong());
    }

    /**
     * 回退发生后，成功必须归属到真正服务的 provider，
     * 否则动态路由会把已经失败的通道当成健康通道继续选它。
     */
    @Test
    void shouldAttributeFallbackStreamOutcomeToServingProvider() {
        stubQuotaPreCheck(100L, 1000L);
        Mockito.when(quotaService.adjustByActualUsage(Mockito.any(), Mockito.eq(8L)))
                .thenReturn(Mono.just(new RedisTokenQuotaService.QuotaSettleResult(8L, 8L, 8L)));
        Mockito.when(providerRoutingService.resolve(Mockito.anyString(), Mockito.any())).thenReturn(AiRoutingResult.builder()
                .provider("openai")
                .providerModel("gpt-4o-mini")
                .upstreamUri("http://primary/v1/chat/completions")
                .routePolicy(routePolicy())
                .fallbackCandidates(List.of(AiRoutingResult.FallbackRouteTarget.builder()
                        .provider("claude")
                        .providerModel("claude-3-5-sonnet-latest")
                        .upstreamUri("http://fallback/v1/messages")
                        .routePolicy(routePolicy())
                        .build()))
                .build());
        // 主通道适配器直通上游流，让上游 500 原样暴露出来
        Mockito.when(providerAdapter.fromUpstreamSse(Mockito.any(), Mockito.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ProviderAdapter claudeAdapter = Mockito.mock(ProviderAdapter.class);
        Mockito.when(claudeAdapter.providerName()).thenReturn("claude");
        Mockito.when(claudeAdapter.toUpstreamRequest(Mockito.any())).thenReturn(Map.of("model", "claude-3-5-sonnet-latest"));
        Mockito.when(claudeAdapter.fromUpstreamSse(Mockito.any(), Mockito.any())).thenReturn(Flux.just(
                "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":6,\"completion_tokens\":2,\"total_tokens\":8}}",
                "[DONE]"));

        AiGatewayService fallbackService = buildService(routeAwareWebClient(), List.of(providerAdapter, claudeAdapter));

        List<String> chunks = fallbackService.streamChatCompletion(request(), httpRequest(), tenantContext()).collectList().block();

        Assertions.assertNotNull(chunks);
        Assertions.assertEquals(3, chunks.size());
        Mockito.verify(providerRoutingService).recordProviderOutcome(Mockito.eq("openai"), Mockito.eq("gpt-4o-mini"),
                Mockito.anyLong(), Mockito.eq(false), Mockito.eq(0L), Mockito.eq(0L));
        Mockito.verify(providerRoutingService).recordProviderOutcome(Mockito.eq("claude"), Mockito.eq("claude-3-5-sonnet-latest"),
                Mockito.anyLong(), Mockito.eq(true), Mockito.eq(6L), Mockito.eq(2L));
    }

    private void stubQuotaPreCheck(long minuteQuota, long dayQuota) {
        Mockito.when(quotaService.preCheck(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(Mono.just(QuotaPreCheckContext.builder()
                        .quotaKey("quota-key")
                        .tenantId("tenant-a")
                        .appId("app-a")
                        .provider("openai")
                        .providerModel("gpt-4o-mini")
                        .reservedTokens(10L)
                        .minuteQuota(minuteQuota)
                        .dayQuota(dayQuota)
                        .monthQuota(dayQuota * 30)
                        .minuteUsedAfterReserve(15L)
                        .dayUsedAfterReserve(15L)
                        .minuteKey("minute")
                        .dayKey("day")
                        .monthKey("month")
                        .build()));
    }

    private AiGatewayProperties.ProviderCredential credential(String apiKey) {
        AiGatewayProperties.ProviderCredential credential = new AiGatewayProperties.ProviderCredential();
        credential.setApiKey(apiKey);
        return credential;
    }

    private ServerHttpRequest httpRequest() {
        return MockServerHttpRequest.post("/v1/chat/completions")
                .header("X-Request-Id", REQUEST_ID)
                .build();
    }

    private TenantContext tenantContext() {
        return new TenantContext("tenant-a", "app-a", "key-a");
    }

    private AiChatCompletionReqDTO request() {
        AiChatCompletionReqDTO request = new AiChatCompletionReqDTO();
        request.setModel("gpt-4o-mini");
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("user");
        message.setContent("hello");
        request.setMessages(List.of(message));
        request.setStream(true);
        return request;
    }
}
