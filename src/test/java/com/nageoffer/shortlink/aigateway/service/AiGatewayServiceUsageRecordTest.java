package com.nageoffer.shortlink.aigateway.service;

import com.nageoffer.shortlink.aigateway.adapter.ProviderAdapter;
import com.nageoffer.shortlink.aigateway.governance.ProviderRateLimitService;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTracer;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.governance.AiCacheStatsService;
import com.nageoffer.shortlink.aigateway.governance.AiCacheControlService;
import com.nageoffer.shortlink.aigateway.governance.AiCacheKeyService;
import com.nageoffer.shortlink.aigateway.governance.AiSafetyGuard;
import com.nageoffer.shortlink.aigateway.governance.NoopSemanticCacheService;
import com.nageoffer.shortlink.aigateway.governance.QuotaPreCheckContext;
import com.nageoffer.shortlink.aigateway.governance.RedisResponseCacheService;
import com.nageoffer.shortlink.aigateway.governance.RedisTokenQuotaService;
import com.nageoffer.shortlink.aigateway.governance.UsageDetail;
import com.nageoffer.shortlink.aigateway.governance.RateLimitHeaderService;
import com.nageoffer.shortlink.aigateway.governance.UpstreamCredentialService;
import com.nageoffer.shortlink.aigateway.governance.UsageExtractor;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.observability.AiCallRecord;
import com.nageoffer.shortlink.aigateway.observability.AiRequestTraceBus;
import com.nageoffer.shortlink.aigateway.observability.AiRequestTraceEvent;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.observability.RequestTracePublisher;
import com.nageoffer.shortlink.aigateway.plugin.PluginChainService;
import com.nageoffer.shortlink.aigateway.plugin.PluginResponseContext;
import com.nageoffer.shortlink.aigateway.routing.AiRoutePolicy;
import com.nageoffer.shortlink.aigateway.routing.AiRoutingResult;
import com.nageoffer.shortlink.aigateway.routing.ProviderRoutingService;
import com.nageoffer.shortlink.aigateway.routing.RoutingPlanResolver;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import com.nageoffer.shortlink.aigateway.tenant.TenantModelPolicyService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

class AiGatewayServiceUsageRecordTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldWriteCacheAndRecordUsageOnSuccessfulUpstreamCall() {
        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        TenantModelPolicyService tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        AiGatewayMetricsRecorder metricsRecorder = Mockito.mock(AiGatewayMetricsRecorder.class);
        AiCacheControlService aiCacheControlService = Mockito.mock(AiCacheControlService.class);
        AiCacheKeyService aiCacheKeyService = Mockito.mock(AiCacheKeyService.class);
        AiCacheStatsService aiCacheStatsService = Mockito.mock(AiCacheStatsService.class);
        RedisResponseCacheService redisResponseCacheService = mockResponseCache();
        RedisTokenQuotaService redisTokenQuotaService = mockQuotaService();
        UsageExtractor usageExtractor = Mockito.mock(UsageExtractor.class);
        PluginChainService pluginChainService = Mockito.mock(PluginChainService.class);
        ProviderAdapter providerAdapter = Mockito.mock(ProviderAdapter.class);
        ReactiveCircuitBreakerFactory circuitBreakerFactory = Mockito.mock(ReactiveCircuitBreakerFactory.class);
        ReactiveCircuitBreaker circuitBreaker = Mockito.mock(ReactiveCircuitBreaker.class);
        AiSafetyGuard aiSafetyGuard = Mockito.mock(AiSafetyGuard.class);

        Mockito.when(tenantModelPolicyService.resolveModel(Mockito.any(), Mockito.anyString())).thenReturn("gpt-4o-mini");
        Mockito.when(providerRoutingService.resolve(Mockito.anyString(), Mockito.any())).thenReturn(AiRoutingResult.builder()
                .provider("openai")
                .providerModel("gpt-4o-mini")
                .upstreamUri("http://primary/v1/chat/completions")
                .routePolicy(routePolicy())
                .build());
        Mockito.when(aiCacheControlService.enabledForRequest(Mockito.any(), eq(false))).thenReturn(true);
        Mockito.when(aiCacheKeyService.build(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any())).thenReturn("cache-key");
        Mockito.when(redisResponseCacheService.get("cache-key")).thenReturn(Mono.empty());
        QuotaPreCheckContext quotaContext = quotaContext();
        Mockito.when(redisTokenQuotaService.preCheck(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(Mono.just(quotaContext));
        Mockito.when(usageExtractor.extractUsage("normalized-body")).thenReturn(UsageDetail.builder()
                .promptTokens(12L)
                .completionTokens(18L)
                .totalTokens(30L)
                .build());
        Mockito.when(providerAdapter.providerName()).thenReturn("openai");
        Mockito.when(providerAdapter.toUpstreamRequest(Mockito.any())).thenReturn(java.util.Map.of("model", "gpt-4o-mini"));
        Mockito.when(providerAdapter.fromUpstreamResponse(Mockito.eq("upstream-body"), Mockito.any())).thenReturn(Mono.just("normalized-body"));
        Mockito.when(pluginChainService.executeAfterResponse(Mockito.any())).thenAnswer(invocation -> ((PluginResponseContext) invocation.getArgument(0)).getResponseBody());
        Mockito.when(aiSafetyGuard.processOutput(Mockito.anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(circuitBreakerFactory.create(Mockito.anyString())).thenReturn(circuitBreaker);
        Mockito.when(circuitBreaker.run(Mockito.any(Mono.class), Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

        AiGatewayProperties properties = new AiGatewayProperties();
        AiRequestTraceBus requestTraceBus = Mockito.mock(AiRequestTraceBus.class);
        RequestTracePublisher tracePublisher = new RequestTracePublisher(requestTraceBus);
        UpstreamCallExecutor upstreamCallExecutor = new UpstreamCallExecutor(
                WebClient.builder().exchangeFunction(successExchangeFunction("upstream-body")).build(),
                List.of(providerAdapter),
                circuitBreakerFactory,
                Mockito.mock(UpstreamCredentialService.class),
                pluginChainService,
                aiSafetyGuard,
                new ProviderRateLimitService(properties),
                providerRoutingService,
                properties,
                tracePublisher
        );
        AiGatewayService service = new AiGatewayService(
                new RoutingPlanResolver(properties, providerRoutingService, tenantModelPolicyService),
                metricsRecorder,
                redisTokenQuotaService,
                usageExtractor,
                aiCacheControlService,
                aiCacheKeyService,
                aiCacheStatsService,
                redisResponseCacheService,
                Mockito.mock(NoopSemanticCacheService.class),
                properties,
                Mockito.mock(AiGatewayTracer.class),
                new RateLimitHeaderService(),
                tracePublisher,
                upstreamCallExecutor
        );

        String body = service.chatCompletion(request(), httpRequest(), new TenantContext("tenant-a", "app-a", "key-a")).block();

        Assertions.assertEquals("normalized-body", body);
        Mockito.verify(aiCacheStatsService).recordMiss();
        Mockito.verify(aiCacheStatsService).recordWrite();
        Mockito.verify(redisResponseCacheService).put("cache-key", "normalized-body", properties.getCache().getTtl());
        Mockito.verify(redisTokenQuotaService).adjustByActualUsage(quotaContext, 30L);
        Mockito.verify(metricsRecorder).recordTenantCacheEvent("tenant-a", "miss");
        Mockito.verify(metricsRecorder).recordTenantCacheEvent("tenant-a", "write");
        ArgumentCaptor<AiCallRecord> captor = ArgumentCaptor.forClass(AiCallRecord.class);
        Mockito.verify(metricsRecorder).recordCall(captor.capture());
        Assertions.assertEquals(12L, captor.getValue().getTokenIn());
        Assertions.assertEquals(18L, captor.getValue().getTokenOut());
        Assertions.assertEquals(Boolean.FALSE, captor.getValue().getCacheHit());
        // 成功结果回灌健康分，token 用量用于估算成本
        Mockito.verify(providerRoutingService).recordProviderOutcome(eq("openai"), eq("gpt-4o-mini"),
                Mockito.anyLong(), eq(true), eq(12L), eq(18L));

        // 实时链路的阶段埋点要齐：否则看板上会出现"黑洞"段落
        ArgumentCaptor<AiRequestTraceEvent> traceCaptor = ArgumentCaptor.forClass(AiRequestTraceEvent.class);
        Mockito.verify(requestTraceBus, Mockito.atLeastOnce()).publish(traceCaptor.capture());
        List<String> stages = traceCaptor.getAllValues().stream().map(AiRequestTraceEvent::getStage).distinct().toList();
        Assertions.assertTrue(stages.containsAll(List.of("routing", "cache", "quota", "upstream", "completed")),
                "缺少阶段: " + stages);
        Mockito.verify(requestTraceBus).publish(Mockito.argThat(event ->
                "cache".equals(event.getStage()) && "miss".equals(event.getStatus())));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldFallbackToSecondaryProviderWhenPrimaryFails() {
        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        TenantModelPolicyService tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        AiGatewayMetricsRecorder metricsRecorder = Mockito.mock(AiGatewayMetricsRecorder.class);
        RedisTokenQuotaService redisTokenQuotaService = mockQuotaService();
        UsageExtractor usageExtractor = Mockito.mock(UsageExtractor.class);
        PluginChainService pluginChainService = Mockito.mock(PluginChainService.class);
        ProviderAdapter openaiAdapter = Mockito.mock(ProviderAdapter.class);
        ProviderAdapter claudeAdapter = Mockito.mock(ProviderAdapter.class);
        ReactiveCircuitBreakerFactory circuitBreakerFactory = Mockito.mock(ReactiveCircuitBreakerFactory.class);
        ReactiveCircuitBreaker circuitBreaker = Mockito.mock(ReactiveCircuitBreaker.class);
        AiSafetyGuard aiSafetyGuard = Mockito.mock(AiSafetyGuard.class);

        Mockito.when(tenantModelPolicyService.resolveModel(Mockito.any(), Mockito.anyString())).thenReturn("gpt-4o-mini");
        Mockito.when(providerRoutingService.resolve(Mockito.anyString(), Mockito.any())).thenReturn(AiRoutingResult.builder()
                .provider("openai")
                .providerModel("gpt-4o-mini")
                .upstreamUri("http://primary/v1/chat/completions")
                .routePolicy(routePolicy())
                .fallbackCandidates(List.of(AiRoutingResult.FallbackRouteTarget.builder()
                        .provider("claude")
                        .providerModel("gpt-4o-mini")
                        .upstreamUri("http://fallback/v1/chat/completions")
                        .routePolicy(routePolicy())
                        .build()))
                .build());
        Mockito.when(redisTokenQuotaService.preCheck(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(Mono.just(quotaContext()));
        Mockito.when(usageExtractor.extractUsage("claude-normalized")).thenReturn(UsageDetail.builder().totalTokens(20L).build());
        Mockito.when(openaiAdapter.providerName()).thenReturn("openai");
        Mockito.when(claudeAdapter.providerName()).thenReturn("claude");
        Mockito.when(openaiAdapter.toUpstreamRequest(Mockito.any())).thenReturn(java.util.Map.of("provider", "openai"));
        Mockito.when(claudeAdapter.toUpstreamRequest(Mockito.any())).thenReturn(java.util.Map.of("provider", "claude"));
        Mockito.when(openaiAdapter.fromUpstreamResponse(Mockito.anyString(), Mockito.any())).thenReturn(Mono.just("openai-normalized"));
        Mockito.when(claudeAdapter.fromUpstreamResponse(Mockito.eq("fallback-body"), Mockito.any())).thenReturn(Mono.just("claude-normalized"));
        Mockito.when(pluginChainService.executeAfterResponse(Mockito.any())).thenAnswer(invocation -> ((PluginResponseContext) invocation.getArgument(0)).getResponseBody());
        Mockito.when(aiSafetyGuard.processOutput(Mockito.anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(circuitBreakerFactory.create(Mockito.anyString())).thenReturn(circuitBreaker);
        Mockito.when(circuitBreaker.run(Mockito.any(Mono.class), Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

        AiGatewayProperties gatewayProperties = new AiGatewayProperties();
        RequestTracePublisher tracePublisher = new RequestTracePublisher(Mockito.mock(AiRequestTraceBus.class));
        UpstreamCallExecutor upstreamCallExecutor = new UpstreamCallExecutor(
                WebClient.builder().exchangeFunction(fallbackExchangeFunction()).build(),
                List.of(openaiAdapter, claudeAdapter),
                circuitBreakerFactory,
                Mockito.mock(UpstreamCredentialService.class),
                pluginChainService,
                aiSafetyGuard,
                new ProviderRateLimitService(gatewayProperties),
                providerRoutingService,
                gatewayProperties,
                tracePublisher
        );
        AiGatewayService service = new AiGatewayService(
                new RoutingPlanResolver(gatewayProperties, providerRoutingService, tenantModelPolicyService),
                metricsRecorder,
                redisTokenQuotaService,
                usageExtractor,
                Mockito.mock(AiCacheControlService.class),
                Mockito.mock(AiCacheKeyService.class),
                Mockito.mock(AiCacheStatsService.class),
                mockResponseCache(),
                Mockito.mock(NoopSemanticCacheService.class),
                gatewayProperties,
                Mockito.mock(AiGatewayTracer.class),
                new RateLimitHeaderService(),
                tracePublisher,
                upstreamCallExecutor
        );

        String body = service.chatCompletion(request(), httpRequest(), new TenantContext("tenant-a", "app-a", "key-a")).block();

        Assertions.assertEquals("claude-normalized", body);
        ArgumentCaptor<AiCallRecord> captor = ArgumentCaptor.forClass(AiCallRecord.class);
        Mockito.verify(metricsRecorder).recordCall(captor.capture());
        Assertions.assertEquals("claude", captor.getValue().getProvider());
        Assertions.assertEquals("gpt-4o-mini", captor.getValue().getModel());
        // 主通道失败与备通道成功各上报一次：只报最终结果的话，失败的通道永远拿不到差评
        Mockito.verify(providerRoutingService).recordProviderOutcome(eq("openai"), eq("gpt-4o-mini"),
                Mockito.anyLong(), eq(false), eq(0L), eq(0L));
        Mockito.verify(providerRoutingService).recordProviderOutcome(eq("claude"), eq("gpt-4o-mini"),
                Mockito.anyLong(), eq(true), eq(0L), eq(0L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRecordTenantContextOnCacheHit() {
        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        TenantModelPolicyService tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        AiGatewayMetricsRecorder metricsRecorder = Mockito.mock(AiGatewayMetricsRecorder.class);
        AiCacheControlService aiCacheControlService = Mockito.mock(AiCacheControlService.class);
        AiCacheKeyService aiCacheKeyService = Mockito.mock(AiCacheKeyService.class);
        RedisResponseCacheService redisResponseCacheService = mockResponseCache();

        Mockito.when(tenantModelPolicyService.resolveModel(Mockito.any(), Mockito.anyString())).thenReturn("gpt-4o-mini");
        Mockito.when(providerRoutingService.resolve(Mockito.anyString(), Mockito.any())).thenReturn(AiRoutingResult.builder().provider("openai").providerModel("gpt-4o-mini").upstreamUri("http://localhost").build());
        Mockito.when(aiCacheControlService.enabledForRequest(Mockito.any(), eq(false))).thenReturn(true);
        Mockito.when(aiCacheKeyService.build(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any())).thenReturn("cache-key");
        Mockito.when(redisResponseCacheService.get("cache-key")).thenReturn(Mono.just("cached-body"));

        AiGatewayProperties gatewayProperties = new AiGatewayProperties();
        RequestTracePublisher tracePublisher = new RequestTracePublisher(Mockito.mock(AiRequestTraceBus.class));
        UpstreamCallExecutor upstreamCallExecutor = new UpstreamCallExecutor(
                WebClient.builder().build(),
                List.<ProviderAdapter>of(),
                Mockito.mock(ReactiveCircuitBreakerFactory.class),
                Mockito.mock(UpstreamCredentialService.class),
                Mockito.mock(PluginChainService.class),
                Mockito.mock(AiSafetyGuard.class),
                new ProviderRateLimitService(gatewayProperties),
                providerRoutingService,
                gatewayProperties,
                tracePublisher
        );
        AiGatewayService service = new AiGatewayService(
                new RoutingPlanResolver(gatewayProperties, providerRoutingService, tenantModelPolicyService),
                metricsRecorder,
                mockQuotaService(),
                Mockito.mock(UsageExtractor.class),
                aiCacheControlService,
                aiCacheKeyService,
                Mockito.mock(AiCacheStatsService.class),
                redisResponseCacheService,
                Mockito.mock(NoopSemanticCacheService.class),
                gatewayProperties,
                Mockito.mock(AiGatewayTracer.class),
                new RateLimitHeaderService(),
                tracePublisher,
                upstreamCallExecutor
        );

        String body = service.chatCompletion(request(), httpRequest(), new TenantContext("tenant-a", "app-a", "key-a")).block();

        Assertions.assertEquals("cached-body", body);
        ArgumentCaptor<AiCallRecord> captor = ArgumentCaptor.forClass(AiCallRecord.class);
        Mockito.verify(metricsRecorder).recordCall(captor.capture());
        Mockito.verify(metricsRecorder).recordTenantCacheEvent("tenant-a", "hit");
        Assertions.assertEquals("tenant-a", captor.getValue().getTenantId());
        Assertions.assertEquals("app-a", captor.getValue().getAppId());
        Assertions.assertEquals("key-a", captor.getValue().getKeyId());
        Assertions.assertEquals(Boolean.TRUE, captor.getValue().getCacheHit());
        // 缓存命中没有打到上游，不能污染 provider 健康度
        Mockito.verify(providerRoutingService, Mockito.never()).recordProviderOutcome(Mockito.anyString(),
                Mockito.anyString(), Mockito.anyLong(), Mockito.anyBoolean(), Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRecordSemanticCacheHitExactlyLikeExactHit() {
        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        TenantModelPolicyService tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        AiGatewayMetricsRecorder metricsRecorder = Mockito.mock(AiGatewayMetricsRecorder.class);
        AiCacheControlService aiCacheControlService = Mockito.mock(AiCacheControlService.class);
        AiCacheKeyService aiCacheKeyService = Mockito.mock(AiCacheKeyService.class);
        AiCacheStatsService aiCacheStatsService = Mockito.mock(AiCacheStatsService.class);
        RedisResponseCacheService redisResponseCacheService = mockResponseCache();
        NoopSemanticCacheService semanticCacheService = Mockito.mock(NoopSemanticCacheService.class);

        Mockito.when(tenantModelPolicyService.resolveModel(Mockito.any(), Mockito.anyString())).thenReturn("gpt-4o-mini");
        Mockito.when(providerRoutingService.resolve(Mockito.anyString(), Mockito.any()))
                .thenReturn(AiRoutingResult.builder().provider("openai").providerModel("gpt-4o-mini").upstreamUri("http://localhost").build());
        Mockito.when(aiCacheControlService.enabledForRequest(Mockito.any(), eq(false))).thenReturn(true);
        Mockito.when(aiCacheKeyService.build(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any())).thenReturn("cache-key");
        // 精确缓存未命中，落到语义缓存
        Mockito.when(redisResponseCacheService.get("cache-key")).thenReturn(Mono.empty());
        Mockito.when(semanticCacheService.find(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(Optional.of("semantic-body"));

        AiGatewayProperties gatewayProperties = new AiGatewayProperties();
        RequestTracePublisher tracePublisher = new RequestTracePublisher(Mockito.mock(AiRequestTraceBus.class));
        UpstreamCallExecutor upstreamCallExecutor = new UpstreamCallExecutor(
                WebClient.builder().build(),
                List.<ProviderAdapter>of(),
                Mockito.mock(ReactiveCircuitBreakerFactory.class),
                Mockito.mock(UpstreamCredentialService.class),
                Mockito.mock(PluginChainService.class),
                Mockito.mock(AiSafetyGuard.class),
                new ProviderRateLimitService(gatewayProperties),
                providerRoutingService,
                gatewayProperties,
                tracePublisher
        );
        AiGatewayService service = new AiGatewayService(
                new RoutingPlanResolver(gatewayProperties, providerRoutingService, tenantModelPolicyService),
                metricsRecorder,
                mockQuotaService(),
                Mockito.mock(UsageExtractor.class),
                aiCacheControlService,
                aiCacheKeyService,
                aiCacheStatsService,
                redisResponseCacheService,
                semanticCacheService,
                gatewayProperties,
                Mockito.mock(AiGatewayTracer.class),
                new RateLimitHeaderService(),
                tracePublisher,
                upstreamCallExecutor
        );

        String body = service.chatCompletion(request(), httpRequest(), new TenantContext("tenant-a", "app-a", "key-a")).block();

        Assertions.assertEquals("semantic-body", body);
        Mockito.verify(aiCacheStatsService).recordSemanticHit();
        // 关键：语义命中也必须留下调用明细，否则"租户 cacheHit 记到了、调用列表里却找不到这次请求"
        ArgumentCaptor<AiCallRecord> captor = ArgumentCaptor.forClass(AiCallRecord.class);
        Mockito.verify(metricsRecorder).recordCall(captor.capture());
        Assertions.assertEquals(Boolean.TRUE, captor.getValue().getCacheHit());
        Assertions.assertEquals(200, captor.getValue().getStatus());
        Assertions.assertEquals("tenant-a", captor.getValue().getTenantId());
        Assertions.assertEquals("app-a", captor.getValue().getAppId());
        Assertions.assertEquals("key-a", captor.getValue().getKeyId());
        Assertions.assertEquals(0L, captor.getValue().getLatencyMillis().longValue());
        // 两类命中对租户聚合都只算一次，不能因为走了两层缓存就重复计数
        Mockito.verify(metricsRecorder, Mockito.times(1)).recordTenantCacheEvent("tenant-a", "hit");
        Mockito.verify(providerRoutingService, Mockito.never()).recordProviderOutcome(Mockito.anyString(),
                Mockito.anyString(), Mockito.anyLong(), Mockito.anyBoolean(), Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBypassExactCacheForStreamingRequest() {
        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        TenantModelPolicyService tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        AiGatewayMetricsRecorder metricsRecorder = Mockito.mock(AiGatewayMetricsRecorder.class);
        RedisResponseCacheService redisResponseCacheService = mockResponseCache();
        RedisTokenQuotaService redisTokenQuotaService = mockQuotaService();

        Mockito.when(tenantModelPolicyService.resolveModel(Mockito.any(), Mockito.anyString())).thenReturn("gpt-4o-mini");
        Mockito.when(providerRoutingService.resolve(Mockito.anyString(), Mockito.any())).thenReturn(AiRoutingResult.builder().provider("openai").providerModel("gpt-4o-mini").upstreamUri("http://localhost").build());
        Mockito.when(redisTokenQuotaService.preCheck(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(Mono.just(QuotaPreCheckContext.builder()
                        .quotaKey("quota-key")
                        .reservedTokens(10L)
                        .minuteQuota(100L)
                        .dayQuota(1000L)
                        .monthQuota(5000L)
                        .minuteKey("minute")
                        .dayKey("day")
                        .monthKey("month")
                        .build()));

        AiGatewayProperties gatewayProperties = new AiGatewayProperties();
        RequestTracePublisher tracePublisher = new RequestTracePublisher(Mockito.mock(AiRequestTraceBus.class));
        UpstreamCallExecutor upstreamCallExecutor = new UpstreamCallExecutor(
                WebClient.builder().build(),
                List.<ProviderAdapter>of(),
                Mockito.mock(ReactiveCircuitBreakerFactory.class),
                Mockito.mock(UpstreamCredentialService.class),
                Mockito.mock(PluginChainService.class),
                Mockito.mock(AiSafetyGuard.class),
                new ProviderRateLimitService(gatewayProperties),
                providerRoutingService,
                gatewayProperties,
                tracePublisher
        );
        AiGatewayService service = new AiGatewayService(
                new RoutingPlanResolver(gatewayProperties, providerRoutingService, tenantModelPolicyService),
                metricsRecorder,
                redisTokenQuotaService,
                Mockito.mock(UsageExtractor.class),
                Mockito.mock(AiCacheControlService.class),
                Mockito.mock(AiCacheKeyService.class),
                Mockito.mock(AiCacheStatsService.class),
                redisResponseCacheService,
                Mockito.mock(NoopSemanticCacheService.class),
                gatewayProperties,
                Mockito.mock(AiGatewayTracer.class),
                new RateLimitHeaderService(),
                tracePublisher,
                upstreamCallExecutor
        );

        // 流式链路无法再改 HTTP 状态码：失败必须转成 OpenAI 兼容错误帧并补 [DONE]，
        // 否则客户端只会看到连接被截断，无法区分"上游挂了"与"网络抖动"。
        java.util.List<String> chunks = service.streamChatCompletion(streamRequest(), httpRequest(),
                        new TenantContext("tenant-a", "app-a", "key-a"))
                .collectList()
                .block();

        Assertions.assertNotNull(chunks);
        Assertions.assertEquals(2, chunks.size());
        Assertions.assertTrue(chunks.get(0).contains("\"error\""), "首个事件应为错误帧: " + chunks.get(0));
        Assertions.assertTrue(chunks.get(0).contains("provider_adapter_not_found"), "错误帧应携带机器可读错误码: " + chunks.get(0));
        Assertions.assertEquals("[DONE]", chunks.get(1));
        Mockito.verifyNoInteractions(redisResponseCacheService);
        Mockito.verify(metricsRecorder, Mockito.never()).recordTenantCacheEvent(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void shouldRejectWhenQuotaPreCheckFailsBeforeUpstreamCall() {
        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        TenantModelPolicyService tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        AiGatewayMetricsRecorder metricsRecorder = Mockito.mock(AiGatewayMetricsRecorder.class);
        RedisTokenQuotaService redisTokenQuotaService = mockQuotaService();
        ProviderAdapter providerAdapter = Mockito.mock(ProviderAdapter.class);
        PluginChainService pluginChainService = Mockito.mock(PluginChainService.class);
        RedisResponseCacheService redisResponseCacheService = mockResponseCache();

        Mockito.when(tenantModelPolicyService.resolveModel(Mockito.any(), Mockito.anyString())).thenReturn("gpt-4o-mini");
        Mockito.when(providerRoutingService.resolve(Mockito.anyString(), Mockito.any())).thenReturn(AiRoutingResult.builder()
                .provider("openai")
                .providerModel("gpt-4o-mini")
                .upstreamUri("http://primary/v1/chat/completions")
                .routePolicy(routePolicy())
                .build());
        Mockito.when(redisTokenQuotaService.preCheck(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(Mono.error(new AiGatewayClientException(AiGatewayErrorCode.QUOTA_EXCEEDED, "Token 配额不足，已触发限流")));

        AiGatewayProperties gatewayProperties = new AiGatewayProperties();
        RequestTracePublisher tracePublisher = new RequestTracePublisher(Mockito.mock(AiRequestTraceBus.class));
        UpstreamCallExecutor upstreamCallExecutor = new UpstreamCallExecutor(
                WebClient.builder().build(),
                List.of(providerAdapter),
                Mockito.mock(ReactiveCircuitBreakerFactory.class),
                Mockito.mock(UpstreamCredentialService.class),
                pluginChainService,
                Mockito.mock(AiSafetyGuard.class),
                new ProviderRateLimitService(gatewayProperties),
                providerRoutingService,
                gatewayProperties,
                tracePublisher
        );
        AiGatewayService service = new AiGatewayService(
                new RoutingPlanResolver(gatewayProperties, providerRoutingService, tenantModelPolicyService),
                metricsRecorder,
                redisTokenQuotaService,
                Mockito.mock(UsageExtractor.class),
                Mockito.mock(AiCacheControlService.class),
                Mockito.mock(AiCacheKeyService.class),
                Mockito.mock(AiCacheStatsService.class),
                redisResponseCacheService,
                Mockito.mock(NoopSemanticCacheService.class),
                gatewayProperties,
                Mockito.mock(AiGatewayTracer.class),
                new RateLimitHeaderService(),
                tracePublisher,
                upstreamCallExecutor
        );

        StepVerifier.create(service.chatCompletion(request(), httpRequest(), new TenantContext("tenant-a", "app-a", "key-a")))
                .expectErrorSatisfies(ex -> {
                    AiGatewayClientException apiException = (AiGatewayClientException) ex;
                    Assertions.assertEquals(AiGatewayErrorCode.QUOTA_EXCEEDED, apiException.getErrorCode());
                    Assertions.assertEquals("Token 配额不足，已触发限流", apiException.getMessage());
                })
                .verify();

        Mockito.verifyNoInteractions(providerAdapter, pluginChainService, redisResponseCacheService);
        Mockito.verify(metricsRecorder, Mockito.never()).recordCall(Mockito.any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldRecordFailureWhenSafetyGuardRejectsResponse() {
        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        TenantModelPolicyService tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        AiGatewayMetricsRecorder metricsRecorder = Mockito.mock(AiGatewayMetricsRecorder.class);
        AiCacheControlService aiCacheControlService = Mockito.mock(AiCacheControlService.class);
        AiCacheKeyService aiCacheKeyService = Mockito.mock(AiCacheKeyService.class);
        AiCacheStatsService aiCacheStatsService = Mockito.mock(AiCacheStatsService.class);
        RedisResponseCacheService redisResponseCacheService = mockResponseCache();
        RedisTokenQuotaService redisTokenQuotaService = mockQuotaService();
        UsageExtractor usageExtractor = Mockito.mock(UsageExtractor.class);
        PluginChainService pluginChainService = Mockito.mock(PluginChainService.class);
        ProviderAdapter providerAdapter = Mockito.mock(ProviderAdapter.class);
        ReactiveCircuitBreakerFactory circuitBreakerFactory = Mockito.mock(ReactiveCircuitBreakerFactory.class);
        ReactiveCircuitBreaker circuitBreaker = Mockito.mock(ReactiveCircuitBreaker.class);
        AiSafetyGuard aiSafetyGuard = Mockito.mock(AiSafetyGuard.class);

        Mockito.when(tenantModelPolicyService.resolveModel(Mockito.any(), Mockito.anyString())).thenReturn("gpt-4o-mini");
        Mockito.when(providerRoutingService.resolve(Mockito.anyString(), Mockito.any())).thenReturn(AiRoutingResult.builder()
                .provider("openai")
                .providerModel("gpt-4o-mini")
                .upstreamUri("http://primary/v1/chat/completions")
                .routePolicy(routePolicy())
                .build());
        Mockito.when(aiCacheControlService.enabledForRequest(Mockito.any(), eq(false))).thenReturn(true);
        Mockito.when(aiCacheKeyService.build(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any())).thenReturn("cache-key");
        Mockito.when(redisResponseCacheService.get("cache-key")).thenReturn(Mono.empty());
        QuotaPreCheckContext quotaContext = quotaContext();
        Mockito.when(redisTokenQuotaService.preCheck(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(Mono.just(quotaContext));
        Mockito.when(providerAdapter.providerName()).thenReturn("openai");
        Mockito.when(providerAdapter.toUpstreamRequest(Mockito.any())).thenReturn(java.util.Map.of("model", "gpt-4o-mini"));
        Mockito.when(providerAdapter.fromUpstreamResponse(Mockito.eq("upstream-body"), Mockito.any())).thenReturn(Mono.just("normalized-body"));
        Mockito.when(pluginChainService.executeAfterResponse(Mockito.any())).thenAnswer(invocation -> ((PluginResponseContext) invocation.getArgument(0)).getResponseBody());
        Mockito.when(aiSafetyGuard.processOutput("normalized-body")).thenThrow(new IllegalStateException("unsafe-response"));
        Mockito.when(circuitBreakerFactory.create(Mockito.anyString())).thenReturn(circuitBreaker);
        Mockito.when(circuitBreaker.run(Mockito.any(Mono.class), Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

        AiGatewayProperties gatewayProperties = new AiGatewayProperties();
        RequestTracePublisher tracePublisher = new RequestTracePublisher(Mockito.mock(AiRequestTraceBus.class));
        UpstreamCallExecutor upstreamCallExecutor = new UpstreamCallExecutor(
                WebClient.builder().exchangeFunction(successExchangeFunction("upstream-body")).build(),
                List.of(providerAdapter),
                circuitBreakerFactory,
                Mockito.mock(UpstreamCredentialService.class),
                pluginChainService,
                aiSafetyGuard,
                new ProviderRateLimitService(gatewayProperties),
                providerRoutingService,
                gatewayProperties,
                tracePublisher
        );
        AiGatewayService service = new AiGatewayService(
                new RoutingPlanResolver(gatewayProperties, providerRoutingService, tenantModelPolicyService),
                metricsRecorder,
                redisTokenQuotaService,
                usageExtractor,
                aiCacheControlService,
                aiCacheKeyService,
                aiCacheStatsService,
                redisResponseCacheService,
                Mockito.mock(NoopSemanticCacheService.class),
                gatewayProperties,
                Mockito.mock(AiGatewayTracer.class),
                new RateLimitHeaderService(),
                tracePublisher,
                upstreamCallExecutor
        );

        RuntimeException exception = Assertions.assertThrows(RuntimeException.class,
                () -> service.chatCompletion(request(), httpRequest(), new TenantContext("tenant-a", "app-a", "key-a")).block());

        Assertions.assertEquals("unsafe-response", exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage());
        Mockito.verify(aiCacheStatsService).recordMiss();
        Mockito.verify(redisTokenQuotaService, Mockito.never()).adjustByActualUsage(Mockito.any(), Mockito.anyLong());
        Mockito.verify(redisResponseCacheService, Mockito.never()).put(Mockito.anyString(), Mockito.anyString(), Mockito.any());
        Mockito.verify(metricsRecorder, Mockito.never()).recordTenantCacheEvent("tenant-a", "write");
        ArgumentCaptor<AiCallRecord> captor = ArgumentCaptor.forClass(AiCallRecord.class);
        Mockito.verify(metricsRecorder).recordCall(captor.capture());
        Assertions.assertEquals(500, captor.getValue().getStatus());
        Assertions.assertEquals(Boolean.FALSE, captor.getValue().getCacheHit());
    }

    private static RedisTokenQuotaService mockQuotaService() {
        RedisTokenQuotaService mock = Mockito.mock(RedisTokenQuotaService.class);
        Mockito.when(mock.adjustByActualUsage(Mockito.any(), Mockito.anyLong())).thenReturn(Mono.empty());
        Mockito.when(mock.release(Mockito.any())).thenReturn(Mono.empty());
        return mock;
    }

    private static RedisResponseCacheService mockResponseCache() {
        RedisResponseCacheService mock = Mockito.mock(RedisResponseCacheService.class);
        Mockito.when(mock.put(Mockito.anyString(), Mockito.anyString(), Mockito.any())).thenReturn(Mono.empty());
        return mock;
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

    private AiChatCompletionReqDTO streamRequest() {
        AiChatCompletionReqDTO request = request();
        request.setStream(true);
        return request;
    }

    private QuotaPreCheckContext quotaContext() {
        return QuotaPreCheckContext.builder()
                .quotaKey("quota-key")
                .reservedTokens(10L)
                .minuteQuota(100L)
                .dayQuota(1000L)
                .monthQuota(5000L)
                .minuteKey("minute")
                .dayKey("day")
                .monthKey("month")
                .build();
    }

    private AiRoutePolicy routePolicy() {
        return AiRoutePolicy.builder()
                .requestTimeout(Duration.ofSeconds(1))
                .maxRetries(0)
                .retryStatusCodes(Set.of(503))
                .build();
    }

    private ExchangeFunction successExchangeFunction(String body) {
        return clientRequest -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .build());
    }

    private ExchangeFunction fallbackExchangeFunction() {
        return clientRequest -> {
            String url = clientRequest.url().toString();
            if (url.contains("primary")) {
                return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("primary-down")
                        .build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("fallback-body")
                    .build());
        };
    }

    private static <T> T eq(T value) {
        return Mockito.eq(value);
    }

    private ServerHttpRequest httpRequest() {
        return MockServerHttpRequest.post("/v1/chat/completions")
                .header("X-Request-Id", "req-usage-record")
                .build();
    }
}