package com.nageoffer.shortlink.aigateway.service;

import com.nageoffer.shortlink.aigateway.adapter.ProviderAdapter;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTracer;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.governance.AiCacheControlService;
import com.nageoffer.shortlink.aigateway.governance.AiCacheKeyService;
import com.nageoffer.shortlink.aigateway.governance.AiCacheStatsService;
import com.nageoffer.shortlink.aigateway.governance.AiSafetyGuard;
import com.nageoffer.shortlink.aigateway.governance.ProviderKeyPoolService;
import com.nageoffer.shortlink.aigateway.governance.ProviderRateLimitService;
import com.nageoffer.shortlink.aigateway.governance.QuotaPreCheckContext;
import com.nageoffer.shortlink.aigateway.governance.RateLimitHeaderService;
import com.nageoffer.shortlink.aigateway.governance.RedisResponseCacheService;
import com.nageoffer.shortlink.aigateway.governance.RedisTokenQuotaService;
import com.nageoffer.shortlink.aigateway.governance.SemanticCacheService;
import com.nageoffer.shortlink.aigateway.governance.UpstreamCredentialService;
import com.nageoffer.shortlink.aigateway.governance.UsageExtractor;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.observability.AiRequestTraceBus;
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
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流式回退的"首字节硬边界"。
 * <p>
 * {@code UpstreamCallExecutor.callStreamWithFallback} 只允许在<b>首字节之前</b>换通道：
 * 一旦本通道已向下游吐过 chunk（{@code chunkServed}），再失败也<b>不再回退</b>，
 * 否则客户端会收到"前半段 A 模型 + 后半段 B 模型"拼接出来的 SSE，并让 usage 统计失真。
 * <p>
 * 这组断言把边界两侧各钉一次：
 * <ul>
 *   <li>首字节前失败 → 回退到备通道，备通道确实被调用一次；</li>
 *   <li>首字节后失败 → 不回退，备通道一次都不能被调用。</li>
 * </ul>
 * 之所以要单独测：{@code docs/load/fault-injection.md} 记明这条边界此前<b>只有单测层面之外的空白</b>
 * ——端到端故障注入里没配成两个可用通道，所以"首字节后不回退"一直没有断言。
 */
class UpstreamCallExecutorStreamFallbackBoundaryTest {

    private static final String REQUEST_ID = "req-stream-boundary";

    private AiGatewayProperties properties;

    private RedisTokenQuotaService quotaService;

    private RateLimitHeaderService rateLimitHeaderService;

    private ProviderRoutingService providerRoutingService;

    private TenantModelPolicyService tenantModelPolicyService;

    private PluginChainService pluginChainService;

    private ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory;

    private AiRequestTraceBus requestTraceBus;

    private ProviderAdapter openaiAdapter;

    private ProviderAdapter claudeAdapter;

    private AiGatewayService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new AiGatewayProperties();
        properties.getUpstream().getProviderCredentials().put("openai", credential("sk-platform"));
        properties.getUpstream().getProviderCredentials().put("claude", credential("sk-platform-claude"));

        quotaService = Mockito.mock(RedisTokenQuotaService.class);
        rateLimitHeaderService = new RateLimitHeaderService();

        providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        // 两个通道：主 openai，备 claude —— 回退链的 B 通道必须是另一个 provider。
        Mockito.when(providerRoutingService.resolve(Mockito.anyString(), Mockito.any())).thenReturn(AiRoutingResult.builder()
                .provider("openai")
                .providerModel("gpt-4o-mini")
                .upstreamUri("http://primary/v1/chat/completions")
                .routePolicy(routePolicy())
                .fallbackCandidates(List.of(AiRoutingResult.FallbackRouteTarget.builder()
                        .provider("claude")
                        .providerModel("claude-3-5-sonnet-latest")
                        .upstreamUri("http://secondary/v1/messages")
                        .routePolicy(routePolicy())
                        .build()))
                .build());

        tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        Mockito.when(tenantModelPolicyService.resolveModel(Mockito.any(), Mockito.anyString())).thenReturn("gpt-4o-mini");

        openaiAdapter = Mockito.mock(ProviderAdapter.class);
        Mockito.when(openaiAdapter.providerName()).thenReturn("openai");
        Mockito.when(openaiAdapter.toUpstreamRequest(Mockito.any())).thenReturn(Map.of("model", "gpt-4o-mini"));

        claudeAdapter = Mockito.mock(ProviderAdapter.class);
        Mockito.when(claudeAdapter.providerName()).thenReturn("claude");
        Mockito.when(claudeAdapter.toUpstreamRequest(Mockito.any())).thenReturn(Map.of("model", "claude-3-5-sonnet-latest"));

        pluginChainService = Mockito.mock(PluginChainService.class);
        Mockito.when(pluginChainService.executeAfterResponse(Mockito.any()))
                .thenAnswer(invocation -> ((PluginResponseContext) invocation.getArgument(0)).getResponseBody());

        requestTraceBus = Mockito.mock(AiRequestTraceBus.class);
        circuitBreakerFactory = Mockito.mock(ReactiveCircuitBreakerFactory.class);
        ReactiveCircuitBreaker circuitBreaker = Mockito.mock(ReactiveCircuitBreaker.class);
        Mockito.when(circuitBreakerFactory.create(Mockito.anyString())).thenReturn(circuitBreaker);
        Mockito.when(circuitBreaker.run(Mockito.any(Flux.class), Mockito.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service = buildService();
    }

    /**
     * 主通道在吐出任何 chunk 之前就失败：应当回退到备通道。
     */
    @Test
    void shouldFallbackWhenPrimaryFailsBeforeFirstChunk() {
        stubQuotaPreCheck();
        // 主通道：未吐任何 chunk 即失败（非 AiGatewayClientException，会被判为通道故障，可回退）
        Mockito.when(openaiAdapter.fromUpstreamSse(Mockito.any(), Mockito.any()))
                .thenReturn(Flux.error(new IllegalStateException("primary down before first byte")));
        // 备通道：正常
        Mockito.when(claudeAdapter.fromUpstreamSse(Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(
                        "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                        "[DONE]"));

        List<String> chunks = service.streamChatCompletion(request(), httpRequest(), tenantContext())
                .collectList().block();

        Assertions.assertNotNull(chunks);
        Assertions.assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("hi")),
                "首字节前失败应回退到备通道并产出内容: " + chunks);
        // 边界的关键断言：备通道被真实调用了一次
        Mockito.verify(claudeAdapter, Mockito.times(1)).fromUpstreamSse(Mockito.any(), Mockito.any());
        // 归属：失败记在主通道，成功记在备通道
        Mockito.verify(providerRoutingService).recordProviderOutcome(Mockito.eq("openai"), Mockito.eq("gpt-4o-mini"),
                Mockito.anyLong(), Mockito.eq(false), Mockito.eq(0L), Mockito.eq(0L));
    }

    /**
     * 主通道已经吐过 chunk 再断开：不得回退，备通道一次都不能被调用。
     */
    @Test
    void shouldNotFallbackAfterFirstChunkWhenPrimaryStreamBreaks() {
        stubQuotaPreCheck();
        // 主通道：吐 2 个 chunk 后再失败
        Mockito.when(openaiAdapter.fromUpstreamSse(Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just("chunk-1", "chunk-2")
                        .concatWith(Flux.error(new IllegalStateException("primary broke mid-stream"))));
        // 备通道：如果被调用就会产出内容——但边界要求它永远不被调用
        Mockito.when(claudeAdapter.fromUpstreamSse(Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(
                        "{\"choices\":[{\"delta\":{\"content\":\"SHOULD-NOT-APPEAR\"}}]}",
                        "[DONE]"));

        List<String> chunks = service.streamChatCompletion(request(), httpRequest(), tenantContext())
                .collectList().block();

        Assertions.assertNotNull(chunks);
        Assertions.assertEquals("chunk-1", chunks.get(0));
        Assertions.assertEquals("chunk-2", chunks.get(1));
        // 已吐的 chunk 后面跟错误帧 + [DONE]，而不是把备通道的内容拼上来
        Assertions.assertTrue(chunks.stream().noneMatch(chunk -> chunk.contains("SHOULD-NOT-APPEAR")),
                "首字节后禁止拼接别的通道内容: " + chunks);
        Assertions.assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("\"error\"")),
                "应下发错误帧而不是静默拼接: " + chunks);
        // 边界的关键断言：备通道一次都没被调用
        Mockito.verify(claudeAdapter, Mockito.never()).fromUpstreamSse(Mockito.any(), Mockito.any());
    }

    private AiGatewayService buildService() {
        RequestTracePublisher tracePublisher = new RequestTracePublisher(requestTraceBus);
        UpstreamCallExecutor upstreamCallExecutor = new UpstreamCallExecutor(
                sseWebClient(),
                List.of(openaiAdapter, claudeAdapter),
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

    /**
     * 上游一律 200 + text/event-stream；真正的"吐 chunk / 中途断流"由适配器的 fromUpstreamSse 注入，
     * 这样失败点正好落在"首字节前 / 首字节后"两侧，而不是卡在响应头之前。
     */
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

    private AiGatewayProperties.ProviderCredential credential(String apiKey) {
        AiGatewayProperties.ProviderCredential credential = new AiGatewayProperties.ProviderCredential();
        credential.setApiKey(apiKey);
        return credential;
    }

    private void stubQuotaPreCheck() {
        Mockito.when(quotaService.preCheck(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(Mono.just(QuotaPreCheckContext.builder()
                        .quotaKey("quota-key")
                        .tenantId("tenant-a")
                        .appId("app-a")
                        .provider("openai")
                        .providerModel("gpt-4o-mini")
                        .reservedTokens(10L)
                        .minuteQuota(100L)
                        .dayQuota(1000L)
                        .monthQuota(30000L)
                        .minuteUsedAfterReserve(15L)
                        .dayUsedAfterReserve(15L)
                        .minuteKey("minute")
                        .dayKey("day")
                        .monthKey("month")
                        .build()));
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
