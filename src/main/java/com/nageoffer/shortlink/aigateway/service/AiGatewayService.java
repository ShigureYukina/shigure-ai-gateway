package com.nageoffer.shortlink.aigateway.service;

import com.nageoffer.shortlink.aigateway.adapter.ProviderAdapter;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.model.AiCanonicalChatRequest;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayUpstreamException;
import com.nageoffer.shortlink.aigateway.governance.AiSafetyGuard;
import com.nageoffer.shortlink.aigateway.governance.AiCacheControlService;
import com.nageoffer.shortlink.aigateway.governance.AiCacheStatsService;
import com.nageoffer.shortlink.aigateway.governance.AiCacheKeyService;
import com.nageoffer.shortlink.aigateway.governance.NoopSemanticCacheService;
import com.nageoffer.shortlink.aigateway.governance.QuotaPreCheckContext;
import com.nageoffer.shortlink.aigateway.governance.RedisResponseCacheService;
import com.nageoffer.shortlink.aigateway.governance.RedisTokenQuotaService;
import com.nageoffer.shortlink.aigateway.governance.UsageDetail;
import com.nageoffer.shortlink.aigateway.governance.UsageExtractor;
import com.nageoffer.shortlink.aigateway.observability.AiCallRecord;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.plugin.PluginChainService;
import com.nageoffer.shortlink.aigateway.plugin.PluginRequestContext;
import com.nageoffer.shortlink.aigateway.plugin.PluginResponseContext;
import com.nageoffer.shortlink.aigateway.routing.AiRoutePolicy;
import com.nageoffer.shortlink.aigateway.routing.AiRoutingResult;
import com.nageoffer.shortlink.aigateway.routing.ProviderRoutingService;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import com.nageoffer.shortlink.aigateway.tenant.TenantModelPolicyService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
/**
 * AI 网关核心编排服务。
 * <p>
 * 职责：路由解析、配额预检、缓存命中、上游调用、重试超时、
 * 安全过滤、插件扩展与可观测埋点。
 */
public class AiGatewayService {

    private final WebClient aiGatewayWebClient;

    private final ProviderRoutingService providerRoutingService;

    private final TenantModelPolicyService tenantModelPolicyService;

    private final List<ProviderAdapter> providerAdapters;

    private final AiGatewayMetricsRecorder metricsRecorder;

    private final AiSafetyGuard aiSafetyGuard;

    private final RedisTokenQuotaService redisTokenQuotaService;

    private final UsageExtractor usageExtractor;

    private final AiCacheControlService aiCacheControlService;

    private final AiCacheKeyService aiCacheKeyService;

    private final AiCacheStatsService aiCacheStatsService;

    private final RedisResponseCacheService redisResponseCacheService;

    private final NoopSemanticCacheService semanticCacheService;

    private final PluginChainService pluginChainService;

    private final AiGatewayProperties properties;

    private final ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public AiGatewayService(WebClient aiGatewayWebClient,
                            ProviderRoutingService providerRoutingService,
                            TenantModelPolicyService tenantModelPolicyService,
                            List<ProviderAdapter> providerAdapters,
                            AiGatewayMetricsRecorder metricsRecorder,
                            AiSafetyGuard aiSafetyGuard,
                            RedisTokenQuotaService redisTokenQuotaService,
                            UsageExtractor usageExtractor,
                            AiCacheControlService aiCacheControlService,
                            AiCacheKeyService aiCacheKeyService,
                            AiCacheStatsService aiCacheStatsService,
                            RedisResponseCacheService redisResponseCacheService,
                            NoopSemanticCacheService semanticCacheService,
                            PluginChainService pluginChainService,
                            AiGatewayProperties properties,
                            ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.aiGatewayWebClient = aiGatewayWebClient;
        this.providerRoutingService = providerRoutingService;
        this.tenantModelPolicyService = tenantModelPolicyService;
        this.providerAdapters = providerAdapters;
        this.metricsRecorder = metricsRecorder;
        this.aiSafetyGuard = aiSafetyGuard;
        this.redisTokenQuotaService = redisTokenQuotaService;
        this.usageExtractor = usageExtractor;
        this.aiCacheControlService = aiCacheControlService;
        this.aiCacheKeyService = aiCacheKeyService;
        this.aiCacheStatsService = aiCacheStatsService;
        this.redisResponseCacheService = redisResponseCacheService;
        this.semanticCacheService = semanticCacheService;
        this.pluginChainService = pluginChainService;
        this.properties = properties;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    /**
     * 非流式聊天补全链路。
     * <p>
     * 主要阶段：路由 -> 缓存 -> 配额 -> 适配转换 -> 上游调用 -> 输出治理 -> 指标记录。
     */
    public Mono<String> chatCompletion(AiChatCompletionReqDTO request, HttpHeaders headers, TenantContext tenantContext) {
        String effectiveModel = tenantModelPolicyService.resolveModel(tenantContext, request.getModel());
        request.setModel(effectiveModel);
        AiRoutingResult routing = providerRoutingService.resolve(effectiveModel, headers);
        String requestId = resolveRequestId(headers);
        HttpHeaders forwardHeaders = buildForwardHeaders(headers, requestId);
        boolean cacheEnabled = aiCacheControlService.enabledForRequest(headers, false);
        String cacheKey = cacheEnabled ? aiCacheKeyService.build(tenantContext, routing.getProvider(), routing.getProviderModel(), request) : null;
        if (cacheEnabled) {
            java.util.Optional<String> cached = redisResponseCacheService.get(cacheKey);
            if (cached.isPresent()) {
                aiCacheStatsService.recordHit();
                metricsRecorder.recordTenantCacheEvent(tenantContext.tenantId(), "hit");
                metricsRecorder.recordCall(AiCallRecord.builder()
                        .requestId(requestId)
                        .provider(routing.getProvider())
                        .model(routing.getProviderModel())
                        .tenantId(tenantContext.tenantId())
                        .appId(tenantContext.appId())
                        .keyId(tenantContext.keyId())
                        .tokenIn(0L)
                        .tokenOut(0L)
                        .latencyMillis(0L)
                        .status(200)
                        .cacheHit(true)
                        .build());
                return Mono.just(cached.get());
            }
            java.util.Optional<String> semanticCached = semanticCacheService.find(routing.getProvider(), routing.getProviderModel(), request);
            if (semanticCached.isPresent()) {
                aiCacheStatsService.recordSemanticHit();
                return Mono.just(semanticCached.get());
            }
            aiCacheStatsService.recordMiss();
            metricsRecorder.recordTenantCacheEvent(tenantContext.tenantId(), "miss");
        }
        QuotaPreCheckContext quotaContext = redisTokenQuotaService.preCheck(tenantContext, headers, routing.getProvider(), routing.getProviderModel(), request);
        List<RouteTarget> routeTargets = buildRouteTargets(routing);
        Instant start = Instant.now();
        return callMonoWithFallback(0, routeTargets, request, forwardHeaders, requestId, start)
                .doOnSuccess(attemptResult -> {
                    String body = attemptResult.body();
                    UsageDetail usageDetail = usageExtractor.extractUsage(body);
                    Long totalTokens = usageDetail == null ? null : usageDetail.getTotalTokens();
                    if (totalTokens != null) {
                        redisTokenQuotaService.adjustByActualUsage(quotaContext, totalTokens);
                    }
                    if (cacheEnabled) {
                        redisResponseCacheService.put(cacheKey, body, properties.getCache().getTtl());
                        semanticCacheService.put(routing.getProvider(), routing.getProviderModel(), request, body);
                        aiCacheStatsService.recordWrite();
                        metricsRecorder.recordTenantCacheEvent(tenantContext.tenantId(), "write");
                    }
                    metricsRecorder.recordCall(AiCallRecord.builder()
                            .requestId(requestId)
                            .provider(attemptResult.provider())
                            .model(attemptResult.providerModel())
                            .tenantId(tenantContext.tenantId())
                            .appId(tenantContext.appId())
                            .keyId(tenantContext.keyId())
                            .tokenIn(usageDetail == null ? 0L : safeLong(usageDetail.getPromptTokens()))
                            .tokenOut(usageDetail == null ? 0L : safeLong(usageDetail.getCompletionTokens()))
                            .latencyMillis(Duration.between(start, Instant.now()).toMillis())
                            .status(200)
                            .cacheHit(false)
                            .build());
                })
                .doOnError(ex -> metricsRecorder.recordCall(AiCallRecord.builder()
                        .requestId(requestId)
                        .provider(routing.getProvider())
                        .model(routing.getProviderModel())
                        .tenantId(tenantContext.tenantId())
                        .appId(tenantContext.appId())
                        .keyId(tenantContext.keyId())
                        .tokenIn(0L)
                        .tokenOut(0L)
                        .latencyMillis(Duration.between(start, Instant.now()).toMillis())
                        .status(resolveStatus(ex))
                        .cacheHit(false)
                        .build()))
                .map(AttemptResult::body);
    }

    /**
     * 流式聊天补全链路（SSE）。
     * <p>
     * 与非流式链路保持一致的路由与治理逻辑，但响应以事件流逐段透传。
     */
    public Flux<String> streamChatCompletion(AiChatCompletionReqDTO request, HttpHeaders headers, TenantContext tenantContext) {
        String effectiveModel = tenantModelPolicyService.resolveModel(tenantContext, request.getModel());
        request.setModel(effectiveModel);
        AiRoutingResult routing = providerRoutingService.resolve(effectiveModel, headers);
        String requestId = resolveRequestId(headers);
        HttpHeaders forwardHeaders = buildForwardHeaders(headers, requestId);
        redisTokenQuotaService.preCheck(tenantContext, headers, routing.getProvider(), routing.getProviderModel(), request);
        List<RouteTarget> routeTargets = buildRouteTargets(routing);
        RouteTarget primaryRoute = routeTargets.get(0);
        Instant start = Instant.now();
        return callStreamWithFallback(0, routeTargets, request, forwardHeaders, requestId, start)
                .doOnComplete(() -> metricsRecorder.recordCall(AiCallRecord.builder()
                        .requestId(requestId)
                        .provider(primaryRoute.provider())
                        .model(primaryRoute.providerModel())
                        .tenantId(tenantContext.tenantId())
                        .appId(tenantContext.appId())
                        .keyId(tenantContext.keyId())
                        .tokenIn(0L)
                        .tokenOut(0L)
                        .latencyMillis(Duration.between(start, Instant.now()).toMillis())
                        .status(200)
                        .cacheHit(false)
                        .build()))
                .doOnError(ex -> metricsRecorder.recordCall(AiCallRecord.builder()
                        .requestId(requestId)
                        .provider(routing.getProvider())
                        .model(routing.getProviderModel())
                        .tenantId(tenantContext.tenantId())
                        .appId(tenantContext.appId())
                        .keyId(tenantContext.keyId())
                        .tokenIn(0L)
                        .tokenOut(0L)
                        .latencyMillis(Duration.between(start, Instant.now()).toMillis())
                        .status(resolveStatus(ex))
                        .cacheHit(false)
                        .build()));
    }

    private Mono<AttemptResult> callMonoWithFallback(int index,
                                                     List<RouteTarget> routeTargets,
                                                     AiChatCompletionReqDTO request,
                                                     HttpHeaders forwardHeaders,
                                                     String requestId,
                                                     Instant start) {
        RouteTarget routeTarget = routeTargets.get(index);
        ProviderAdapter adapter = resolveAdapter(routeTarget.provider());
        AiCanonicalChatRequest canonicalRequest = buildCanonicalRequest(request, routeTarget.provider(), routeTarget.providerModel());
        pluginChainService.executeBeforeRequest(PluginRequestContext.builder()
                .provider(routeTarget.provider())
                .model(routeTarget.providerModel())
                .requestId(requestId)
                .request(request)
                .headers(forwardHeaders)
                .build());
        Object upstreamRequestBody = adapter.toUpstreamRequest(canonicalRequest);
        return aiGatewayWebClient.post()
                .uri(routeTarget.upstreamUri())
                .headers(httpHeaders -> httpHeaders.addAll(forwardHeaders))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(upstreamRequestBody)
                .exchangeToMono(response -> handleUpstreamMonoResponse(response.statusCode(), response.bodyToMono(String.class).defaultIfEmpty(""), routeTarget.routePolicy()))
                .transform(source -> circuitBreaker(routeTarget.provider()).run(source, Mono::error))
                .retryWhen(createRetry(routeTarget.routePolicy()))
                .timeout(resolveTimeout(routeTarget.routePolicy()))
                .flatMap(upstreamBody -> adapter.fromUpstreamResponse(upstreamBody, canonicalRequest))
                .map(responseBody -> pluginChainService.executeAfterResponse(PluginResponseContext.builder()
                        .provider(routeTarget.provider())
                        .model(routeTarget.providerModel())
                        .requestId(requestId)
                        .responseBody(responseBody)
                        .latencyMillis(Duration.between(start, Instant.now()).toMillis())
                        .build()))
                .map(aiSafetyGuard::processOutput)
                .map(responseBody -> new AttemptResult(routeTarget.provider(), routeTarget.providerModel(), responseBody))
                .onErrorResume(ex -> {
                    if (index < routeTargets.size() - 1 && shouldFallback(ex)) {
                        return callMonoWithFallback(index + 1, routeTargets, request, forwardHeaders, requestId, start);
                    }
                    return Mono.error(ex);
                });
    }

    private Flux<String> callStreamWithFallback(int index,
                                                List<RouteTarget> routeTargets,
                                                AiChatCompletionReqDTO request,
                                                HttpHeaders forwardHeaders,
                                                String requestId,
                                                Instant start) {
        RouteTarget routeTarget = routeTargets.get(index);
        ProviderAdapter adapter = resolveAdapter(routeTarget.provider());
        AiCanonicalChatRequest canonicalRequest = buildCanonicalRequest(request, routeTarget.provider(), routeTarget.providerModel());
        pluginChainService.executeBeforeRequest(PluginRequestContext.builder()
                .provider(routeTarget.provider())
                .model(routeTarget.providerModel())
                .requestId(requestId)
                .request(request)
                .headers(forwardHeaders)
                .build());
        Object upstreamRequestBody = adapter.toUpstreamRequest(canonicalRequest);
        return aiGatewayWebClient.post()
                .uri(routeTarget.upstreamUri())
                .headers(httpHeaders -> httpHeaders.addAll(forwardHeaders))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(upstreamRequestBody)
                .exchangeToFlux(response -> handleUpstreamFluxResponse(response.statusCode(), response.bodyToFlux(String.class), response.bodyToMono(String.class).defaultIfEmpty(""), routeTarget.routePolicy()))
                .transform(source -> circuitBreaker(routeTarget.provider()).run(source, Flux::error))
                .retryWhen(createRetry(routeTarget.routePolicy()))
                .timeout(resolveTimeout(routeTarget.routePolicy()))
                .transform(upstreamFlux -> adapter.fromUpstreamSse(upstreamFlux, canonicalRequest))
                .map(responseBody -> pluginChainService.executeAfterResponse(PluginResponseContext.builder()
                        .provider(routeTarget.provider())
                        .model(routeTarget.providerModel())
                        .requestId(requestId)
                        .responseBody(responseBody)
                        .latencyMillis(Duration.between(start, Instant.now()).toMillis())
                        .build()))
                .map(aiSafetyGuard::processOutput)
                .onErrorResume(ex -> {
                    if (index < routeTargets.size() - 1 && shouldFallback(ex)) {
                        return callStreamWithFallback(index + 1, routeTargets, request, forwardHeaders, requestId, start);
                    }
                    return Flux.error(ex);
                });
    }

    private boolean shouldFallback(Throwable throwable) {
        return !(throwable instanceof AiGatewayClientException);
    }

    private List<RouteTarget> buildRouteTargets(AiRoutingResult routing) {
        List<RouteTarget> targets = new ArrayList<>();
        targets.add(new RouteTarget(routing.getProvider(), routing.getProviderModel(), routing.getUpstreamUri(), routing.getRoutePolicy()));
        if (routing.getFallbackCandidates() != null) {
            for (AiRoutingResult.FallbackRouteTarget fallbackCandidate : routing.getFallbackCandidates()) {
                targets.add(new RouteTarget(
                        fallbackCandidate.getProvider(),
                        fallbackCandidate.getProviderModel(),
                        fallbackCandidate.getUpstreamUri(),
                        fallbackCandidate.getRoutePolicy()
                ));
            }
        }
        return targets;
    }

    /**
     * 处理非流式上游响应：2xx 直接返回，其余转为统一网关异常。
     */
    private Mono<String> handleUpstreamMonoResponse(HttpStatusCode statusCode, Mono<String> body, AiRoutePolicy routePolicy) {
        if (statusCode.is2xxSuccessful()) {
            return body;
        }
        return body.flatMap(errorBody -> Mono.error(buildUpstreamException(statusCode.value(), errorBody, routePolicy)));
    }

    /**
     * 处理流式上游响应：2xx 继续输出流，其余读取错误体并抛错。
     */
    private Flux<String> handleUpstreamFluxResponse(HttpStatusCode statusCode, Flux<String> fluxBody, Mono<String> monoBody, AiRoutePolicy routePolicy) {
        if (statusCode.is2xxSuccessful()) {
            return fluxBody;
        }
        return monoBody.flatMapMany(errorBody -> Mono.error(buildUpstreamException(statusCode.value(), errorBody, routePolicy)));
    }

    /**
     * 将上游错误包装为可重试/不可重试异常，供重试策略判定。
     */
    private RuntimeException buildUpstreamException(int statusCode, String body, AiRoutePolicy routePolicy) {
        boolean retriable = routePolicy.getRetryStatusCodes().contains(statusCode);
        return new AiGatewayUpstreamException(statusCode, "上游响应异常: status=" + statusCode + ", body=" + body, retriable);
    }

    /**
     * 基于路由策略构建 Reactor 重试器。
     */
    private Retry createRetry(AiRoutePolicy routePolicy) {
        Integer maxRetries = routePolicy.getMaxRetries();
        if (maxRetries == null || maxRetries <= 0) {
            return Retry.max(0);
        }
        return Retry.max(maxRetries)
                .filter(retriableExceptionFilter())
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> new AiGatewayClientException(AiGatewayErrorCode.UPSTREAM_RETRY_EXHAUSTED,
                        "上游重试耗尽: " + retrySignal.failure().getMessage()));
    }

    /**
     * 仅对标记为可重试的上游异常执行重试。
     */
    private Predicate<Throwable> retriableExceptionFilter() {
        return throwable -> throwable instanceof AiGatewayUpstreamException upstreamException && upstreamException.isRetriable();
    }

    /**
     * 路由级超时优先；未配置时回退到全局读超时。
     */
    private Duration resolveTimeout(AiRoutePolicy routePolicy) {
        return routePolicy.getRequestTimeout() == null ? properties.getTimeoutRetry().getReadTimeout() : routePolicy.getRequestTimeout();
    }

    /**
     * 依据 provider 名称解析对应适配器。
     */
    private ProviderAdapter resolveAdapter(String provider) {
        return providerAdapters.stream()
                .filter(each -> each.providerName().equals(provider))
                .findFirst()
                .orElseThrow(() -> new AiGatewayClientException(AiGatewayErrorCode.PROVIDER_ADAPTER_NOT_FOUND, "未找到Provider适配器: " + provider));
    }

    /**
     * 将统一请求 DTO 转为网关规范请求模型。
     */
    private AiCanonicalChatRequest buildCanonicalRequest(AiChatCompletionReqDTO request, String provider, String providerModel) {
        request.getMessages().forEach(each -> aiSafetyGuard.verifyInput(each.getContent()));
        List<Map<String, String>> messages = request.getMessages().stream()
                .map(each -> Map.of("role", each.getRole(), "content", each.getContent()))
                .collect(Collectors.toList());
        return AiCanonicalChatRequest.builder()
                .provider(provider)
                .clientModel(request.getModel())
                .providerModel(providerModel)
                .stream(Boolean.TRUE.equals(request.getStream()))
                .temperature(request.getTemperature())
                .maxTokens(request.getMaxTokens())
                .messages(messages)
                .metadata(request.getMetadata())
                .build();
    }

    private record RouteTarget(String provider, String providerModel, String upstreamUri, AiRoutePolicy routePolicy) {
    }

    private record AttemptResult(String provider, String providerModel, String body) {
    }

    /**
     * 组装透传到上游的请求头，并补齐请求链路追踪 ID。
     */
    private HttpHeaders buildForwardHeaders(HttpHeaders source, String requestId) {
        HttpHeaders target = new HttpHeaders();
        String authorization = source.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null) {
            target.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        target.set("X-Request-Id", requestId);
        String apiKey = source.getFirst("x-api-key");
        if (apiKey != null) {
            target.set("x-api-key", apiKey);
        }
        String anthropicVersion = source.getFirst("anthropic-version");
        if (anthropicVersion != null) {
            target.set("anthropic-version", anthropicVersion);
        }
        return target;
    }

    /**
     * 优先复用客户端传入的 X-Request-Id；若无则网关生成。
     */
    private String resolveRequestId(HttpHeaders headers) {
        String requestId = headers.getFirst("X-Request-Id");
        return requestId == null ? UUID.randomUUID().toString() : requestId;
    }

    /**
     * 将不同异常类型统一映射为可观测状态码。
     */
    private Integer resolveStatus(Throwable throwable) {
        if (throwable instanceof AiGatewayClientException clientException) {
            return clientException.getErrorCode().getStatus();
        }
        if (throwable instanceof AiGatewayUpstreamException upstreamException) {
            return upstreamException.getStatus();
        }
        return 500;
    }

    /**
     * 安全处理可空 Long，避免空指针。
     */
    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private ReactiveCircuitBreaker circuitBreaker(String provider) {
        return circuitBreakerFactory.create("provider-" + provider);
    }
}
