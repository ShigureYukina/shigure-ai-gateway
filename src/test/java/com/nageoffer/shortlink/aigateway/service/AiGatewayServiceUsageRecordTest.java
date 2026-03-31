package com.nageoffer.shortlink.aigateway.service;

import com.nageoffer.shortlink.aigateway.adapter.ProviderAdapter;
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
import com.nageoffer.shortlink.aigateway.governance.UsageExtractor;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.observability.AiCallRecord;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.plugin.PluginChainService;
import com.nageoffer.shortlink.aigateway.plugin.PluginResponseContext;
import com.nageoffer.shortlink.aigateway.routing.AiRoutePolicy;
import com.nageoffer.shortlink.aigateway.routing.AiRoutingResult;
import com.nageoffer.shortlink.aigateway.routing.ProviderRoutingService;
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
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

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
        RedisResponseCacheService redisResponseCacheService = Mockito.mock(RedisResponseCacheService.class);
        RedisTokenQuotaService redisTokenQuotaService = Mockito.mock(RedisTokenQuotaService.class);
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
        Mockito.when(redisResponseCacheService.get("cache-key")).thenReturn(Optional.empty());
        QuotaPreCheckContext quotaContext = quotaContext();
        Mockito.when(redisTokenQuotaService.preCheck(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(quotaContext);
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
        AiGatewayService service = new AiGatewayService(
                WebClient.builder().exchangeFunction(successExchangeFunction("upstream-body")).build(),
                providerRoutingService,
                tenantModelPolicyService,
                List.of(providerAdapter),
                metricsRecorder,
                aiSafetyGuard,
                redisTokenQuotaService,
                usageExtractor,
                aiCacheControlService,
                aiCacheKeyService,
                aiCacheStatsService,
                redisResponseCacheService,
                Mockito.mock(NoopSemanticCacheService.class),
                pluginChainService,
                properties,
                circuitBreakerFactory,
                Mockito.mock(AiGatewayTracer.class)
        );

        String body = service.chatCompletion(request(), new HttpHeaders(), new TenantContext("tenant-a", "app-a", "key-a")).block();

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
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldFallbackToSecondaryProviderWhenPrimaryFails() {
        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        TenantModelPolicyService tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        AiGatewayMetricsRecorder metricsRecorder = Mockito.mock(AiGatewayMetricsRecorder.class);
        RedisTokenQuotaService redisTokenQuotaService = Mockito.mock(RedisTokenQuotaService.class);
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
                .thenReturn(quotaContext());
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

        AiGatewayService service = new AiGatewayService(
                WebClient.builder().exchangeFunction(fallbackExchangeFunction()).build(),
                providerRoutingService,
                tenantModelPolicyService,
                List.of(openaiAdapter, claudeAdapter),
                metricsRecorder,
                aiSafetyGuard,
                redisTokenQuotaService,
                usageExtractor,
                Mockito.mock(AiCacheControlService.class),
                Mockito.mock(AiCacheKeyService.class),
                Mockito.mock(AiCacheStatsService.class),
                Mockito.mock(RedisResponseCacheService.class),
                Mockito.mock(NoopSemanticCacheService.class),
                pluginChainService,
                new AiGatewayProperties(),
                circuitBreakerFactory,
                Mockito.mock(AiGatewayTracer.class)
        );

        String body = service.chatCompletion(request(), new HttpHeaders(), new TenantContext("tenant-a", "app-a", "key-a")).block();

        Assertions.assertEquals("claude-normalized", body);
        ArgumentCaptor<AiCallRecord> captor = ArgumentCaptor.forClass(AiCallRecord.class);
        Mockito.verify(metricsRecorder).recordCall(captor.capture());
        Assertions.assertEquals("claude", captor.getValue().getProvider());
        Assertions.assertEquals("gpt-4o-mini", captor.getValue().getModel());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRecordTenantContextOnCacheHit() {
        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        TenantModelPolicyService tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        AiGatewayMetricsRecorder metricsRecorder = Mockito.mock(AiGatewayMetricsRecorder.class);
        AiCacheControlService aiCacheControlService = Mockito.mock(AiCacheControlService.class);
        AiCacheKeyService aiCacheKeyService = Mockito.mock(AiCacheKeyService.class);
        RedisResponseCacheService redisResponseCacheService = Mockito.mock(RedisResponseCacheService.class);

        Mockito.when(tenantModelPolicyService.resolveModel(Mockito.any(), Mockito.anyString())).thenReturn("gpt-4o-mini");
        Mockito.when(providerRoutingService.resolve(Mockito.anyString(), Mockito.any())).thenReturn(AiRoutingResult.builder().provider("openai").providerModel("gpt-4o-mini").upstreamUri("http://localhost").build());
        Mockito.when(aiCacheControlService.enabledForRequest(Mockito.any(), eq(false))).thenReturn(true);
        Mockito.when(aiCacheKeyService.build(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any())).thenReturn("cache-key");
        Mockito.when(redisResponseCacheService.get("cache-key")).thenReturn(Optional.of("cached-body"));

        AiGatewayService service = new AiGatewayService(
                WebClient.builder().build(),
                providerRoutingService,
                tenantModelPolicyService,
                List.<ProviderAdapter>of(),
                metricsRecorder,
                Mockito.mock(AiSafetyGuard.class),
                Mockito.mock(RedisTokenQuotaService.class),
                Mockito.mock(UsageExtractor.class),
                aiCacheControlService,
                aiCacheKeyService,
                Mockito.mock(AiCacheStatsService.class),
                redisResponseCacheService,
                Mockito.mock(NoopSemanticCacheService.class),
                Mockito.mock(PluginChainService.class),
                new AiGatewayProperties(),
                Mockito.mock(ReactiveCircuitBreakerFactory.class),
                Mockito.mock(AiGatewayTracer.class)
        );

        String body = service.chatCompletion(request(), new HttpHeaders(), new TenantContext("tenant-a", "app-a", "key-a")).block();

        Assertions.assertEquals("cached-body", body);
        ArgumentCaptor<AiCallRecord> captor = ArgumentCaptor.forClass(AiCallRecord.class);
        Mockito.verify(metricsRecorder).recordCall(captor.capture());
        Mockito.verify(metricsRecorder).recordTenantCacheEvent("tenant-a", "hit");
        Assertions.assertEquals("tenant-a", captor.getValue().getTenantId());
        Assertions.assertEquals("app-a", captor.getValue().getAppId());
        Assertions.assertEquals("key-a", captor.getValue().getKeyId());
        Assertions.assertEquals(Boolean.TRUE, captor.getValue().getCacheHit());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBypassExactCacheForStreamingRequest() {
        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        TenantModelPolicyService tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        AiGatewayMetricsRecorder metricsRecorder = Mockito.mock(AiGatewayMetricsRecorder.class);
        RedisResponseCacheService redisResponseCacheService = Mockito.mock(RedisResponseCacheService.class);
        RedisTokenQuotaService redisTokenQuotaService = Mockito.mock(RedisTokenQuotaService.class);

        Mockito.when(tenantModelPolicyService.resolveModel(Mockito.any(), Mockito.anyString())).thenReturn("gpt-4o-mini");
        Mockito.when(providerRoutingService.resolve(Mockito.anyString(), Mockito.any())).thenReturn(AiRoutingResult.builder().provider("openai").providerModel("gpt-4o-mini").upstreamUri("http://localhost").build());
        Mockito.when(redisTokenQuotaService.preCheck(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(QuotaPreCheckContext.builder()
                        .quotaKey("quota-key")
                        .reservedTokens(10L)
                        .minuteQuota(100L)
                        .dayQuota(1000L)
                        .monthQuota(5000L)
                        .minuteKey("minute")
                        .dayKey("day")
                        .monthKey("month")
                        .build());

        AiGatewayService service = new AiGatewayService(
                WebClient.builder().build(),
                providerRoutingService,
                tenantModelPolicyService,
                List.<ProviderAdapter>of(),
                metricsRecorder,
                Mockito.mock(AiSafetyGuard.class),
                redisTokenQuotaService,
                Mockito.mock(UsageExtractor.class),
                Mockito.mock(AiCacheControlService.class),
                Mockito.mock(AiCacheKeyService.class),
                Mockito.mock(AiCacheStatsService.class),
                redisResponseCacheService,
                Mockito.mock(NoopSemanticCacheService.class),
                Mockito.mock(PluginChainService.class),
                new AiGatewayProperties(),
                Mockito.mock(ReactiveCircuitBreakerFactory.class),
                Mockito.mock(AiGatewayTracer.class)
        );

        RuntimeException exception = Assertions.assertThrows(RuntimeException.class,
                () -> service.streamChatCompletion(streamRequest(), new HttpHeaders(), new TenantContext("tenant-a", "app-a", "key-a"))
                        .collectList()
                        .block());

        Assertions.assertNotNull(exception);
        Mockito.verifyNoInteractions(redisResponseCacheService);
        Mockito.verify(metricsRecorder, Mockito.never()).recordTenantCacheEvent(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void shouldRejectWhenQuotaPreCheckFailsBeforeUpstreamCall() {
        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        TenantModelPolicyService tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        AiGatewayMetricsRecorder metricsRecorder = Mockito.mock(AiGatewayMetricsRecorder.class);
        RedisTokenQuotaService redisTokenQuotaService = Mockito.mock(RedisTokenQuotaService.class);
        ProviderAdapter providerAdapter = Mockito.mock(ProviderAdapter.class);
        PluginChainService pluginChainService = Mockito.mock(PluginChainService.class);
        RedisResponseCacheService redisResponseCacheService = Mockito.mock(RedisResponseCacheService.class);

        Mockito.when(tenantModelPolicyService.resolveModel(Mockito.any(), Mockito.anyString())).thenReturn("gpt-4o-mini");
        Mockito.when(providerRoutingService.resolve(Mockito.anyString(), Mockito.any())).thenReturn(AiRoutingResult.builder()
                .provider("openai")
                .providerModel("gpt-4o-mini")
                .upstreamUri("http://primary/v1/chat/completions")
                .routePolicy(routePolicy())
                .build());
        Mockito.when(redisTokenQuotaService.preCheck(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenThrow(new AiGatewayClientException(AiGatewayErrorCode.QUOTA_EXCEEDED, "Token 配额不足，已触发限流"));

        AiGatewayService service = new AiGatewayService(
                WebClient.builder().build(),
                providerRoutingService,
                tenantModelPolicyService,
                List.of(providerAdapter),
                metricsRecorder,
                Mockito.mock(AiSafetyGuard.class),
                redisTokenQuotaService,
                Mockito.mock(UsageExtractor.class),
                Mockito.mock(AiCacheControlService.class),
                Mockito.mock(AiCacheKeyService.class),
                Mockito.mock(AiCacheStatsService.class),
                redisResponseCacheService,
                Mockito.mock(NoopSemanticCacheService.class),
                pluginChainService,
                new AiGatewayProperties(),
                Mockito.mock(ReactiveCircuitBreakerFactory.class),
                Mockito.mock(AiGatewayTracer.class)
        );

        AiGatewayClientException exception = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.chatCompletion(request(), new HttpHeaders(), new TenantContext("tenant-a", "app-a", "key-a")));

        Assertions.assertEquals(AiGatewayErrorCode.QUOTA_EXCEEDED, exception.getErrorCode());
        Assertions.assertEquals("Token 配额不足，已触发限流", exception.getMessage());
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
        RedisResponseCacheService redisResponseCacheService = Mockito.mock(RedisResponseCacheService.class);
        RedisTokenQuotaService redisTokenQuotaService = Mockito.mock(RedisTokenQuotaService.class);
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
        Mockito.when(redisResponseCacheService.get("cache-key")).thenReturn(Optional.empty());
        QuotaPreCheckContext quotaContext = quotaContext();
        Mockito.when(redisTokenQuotaService.preCheck(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(quotaContext);
        Mockito.when(providerAdapter.providerName()).thenReturn("openai");
        Mockito.when(providerAdapter.toUpstreamRequest(Mockito.any())).thenReturn(java.util.Map.of("model", "gpt-4o-mini"));
        Mockito.when(providerAdapter.fromUpstreamResponse(Mockito.eq("upstream-body"), Mockito.any())).thenReturn(Mono.just("normalized-body"));
        Mockito.when(pluginChainService.executeAfterResponse(Mockito.any())).thenAnswer(invocation -> ((PluginResponseContext) invocation.getArgument(0)).getResponseBody());
        Mockito.when(aiSafetyGuard.processOutput("normalized-body")).thenThrow(new IllegalStateException("unsafe-response"));
        Mockito.when(circuitBreakerFactory.create(Mockito.anyString())).thenReturn(circuitBreaker);
        Mockito.when(circuitBreaker.run(Mockito.any(Mono.class), Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

        AiGatewayService service = new AiGatewayService(
                WebClient.builder().exchangeFunction(successExchangeFunction("upstream-body")).build(),
                providerRoutingService,
                tenantModelPolicyService,
                List.of(providerAdapter),
                metricsRecorder,
                aiSafetyGuard,
                redisTokenQuotaService,
                usageExtractor,
                aiCacheControlService,
                aiCacheKeyService,
                aiCacheStatsService,
                redisResponseCacheService,
                Mockito.mock(NoopSemanticCacheService.class),
                pluginChainService,
                new AiGatewayProperties(),
                circuitBreakerFactory,
                Mockito.mock(AiGatewayTracer.class)
        );

        RuntimeException exception = Assertions.assertThrows(RuntimeException.class,
                () -> service.chatCompletion(request(), new HttpHeaders(), new TenantContext("tenant-a", "app-a", "key-a")).block());

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
}
