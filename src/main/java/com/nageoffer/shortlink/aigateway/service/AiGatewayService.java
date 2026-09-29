package com.nageoffer.shortlink.aigateway.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTracer;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorMapper;
import com.nageoffer.shortlink.aigateway.governance.AiCacheControlService;
import com.nageoffer.shortlink.aigateway.governance.AiCacheKeyService;
import com.nageoffer.shortlink.aigateway.governance.AiCacheStatsService;
import com.nageoffer.shortlink.aigateway.governance.QuotaPreCheckContext;
import com.nageoffer.shortlink.aigateway.governance.RateLimitHeaderService;
import com.nageoffer.shortlink.aigateway.governance.RedisResponseCacheService;
import com.nageoffer.shortlink.aigateway.governance.RedisTokenQuotaService;
import com.nageoffer.shortlink.aigateway.governance.SemanticCacheService;
import com.nageoffer.shortlink.aigateway.governance.StreamUsageCollector;
import com.nageoffer.shortlink.aigateway.governance.UsageDetail;
import com.nageoffer.shortlink.aigateway.governance.UsageExtractor;
import com.nageoffer.shortlink.aigateway.observability.AiCallRecord;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.observability.AiRequestTraceEvent;
import com.nageoffer.shortlink.aigateway.observability.RequestTracePublisher;
import com.nageoffer.shortlink.aigateway.routing.AiRoutingResult;
import com.nageoffer.shortlink.aigateway.routing.RoutingPlanResolver;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import io.micrometer.tracing.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 网关核心编排服务。
 * <p>
 * 职责：路由解析、配额预检与结算、缓存命中、重试超时、
 * 安全过滤、插件扩展与可观测埋点。
 * <p>
 * 边界：真正"把请求打出去"的部分（协议转换、凭证注入、熔断重试超时、回退归因）在
 * {@link UpstreamCallExecutor}；链路事件装配在 {@link RequestTracePublisher}。
 * 本类只决定"这次请求该怎么走、怎么结账"。
 * <p>
 * 构造参数顺序由字段声明顺序决定（{@code @RequiredArgsConstructor}），测试按位置构造。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiGatewayService {

    private static final String DONE_PAYLOAD = "[DONE]";

    /**
     * 允许透传给上游的客户端头白名单。
     * <p>
     * 采用白名单而非黑名单：Authorization 承载的是平台身份，绝不能再外流；
     * 其余未知头也可能携带内部信息，默认丢弃更安全。
     */
    private static final Set<String> FORWARDED_CLIENT_HEADERS = Set.of(
            "OpenAI-Beta",
            "OpenAI-Organization",
            "Idempotency-Key"
    );

    private final RoutingPlanResolver routingPlanResolver;

    private final AiGatewayMetricsRecorder metricsRecorder;

    private final RedisTokenQuotaService redisTokenQuotaService;

    private final UsageExtractor usageExtractor;

    private final AiCacheControlService aiCacheControlService;

    private final AiCacheKeyService aiCacheKeyService;

    private final AiCacheStatsService aiCacheStatsService;

    private final RedisResponseCacheService redisResponseCacheService;

    private final SemanticCacheService semanticCacheService;

    private final AiGatewayProperties properties;

    private final AiGatewayTracer aiGatewayTracer;

    private final RateLimitHeaderService rateLimitHeaderService;

    private final RequestTracePublisher tracePublisher;

    private final UpstreamCallExecutor upstreamCallExecutor;

    /**
     * 非流式聊天补全链路。
     * <p>
     * 主要阶段：路由 -> 缓存 -> 配额 -> 适配转换 -> 上游调用 -> 输出治理 -> 结算与指标。
     */
    public Mono<String> chatCompletion(AiChatCompletionReqDTO request, HttpHeaders headers, TenantContext tenantContext) {
        Span span = aiGatewayTracer.startSpan("ai-gateway.chat-completion");
        RoutingPlanResolver.RoutingPlan plan = routingPlanResolver.resolve(tenantContext, request.getModel(), headers);
        // 生效模型必须回写：租户映射后的名字才是真正要发的，也是缓存键的依据
        request.setModel(plan.effectiveModel());
        AiRoutingResult routing = plan.routing();
        String requestId = resolveRequestId(headers);
        aiGatewayTracer.tag(span, "request.id", requestId);
        aiGatewayTracer.tag(span, "provider", routing.getProvider());
        aiGatewayTracer.tag(span, "model", routing.getProviderModel());
        aiGatewayTracer.tag(span, "tenant.id", tenantContext.tenantId());
        aiGatewayTracer.tag(span, "route.source", routing.getRouteSource());
        tracePublisher.publish(requestId, tenantContext, AiRequestTraceEvent.STAGE_ROUTING, "ok",
                routing.getProvider(), routing.getProviderModel(), "routeSource=" + routing.getRouteSource(), null, Boolean.FALSE);
        HttpHeaders forwardHeaders = buildForwardHeaders(headers, requestId);
        boolean cacheEnabled = aiCacheControlService.enabledForRequest(headers, false);
        String cacheKey = cacheEnabled ? aiCacheKeyService.build(tenantContext, routing.getProvider(), routing.getProviderModel(), request) : null;
        List<RouteTarget> routeTargets = RouteTarget.from(routing);

        Mono<String> cachedResponse = cacheEnabled
                ? loadFromCache(cacheKey, span, routing, request, tenantContext, requestId)
                : Mono.empty();

        return cachedResponse.switchIfEmpty(Mono.defer(() -> {
            // start 必须在缓存未命中之后取：把它提前会把缓存查找耗时算进上游延迟，
            // 直接污染 provider 健康分与调用明细里的 latency。
            Instant start = Instant.now();
            UpstreamCallContext callContext = new UpstreamCallContext(
                    routeTargets, request, forwardHeaders, requestId, tenantContext, start);
            return redisTokenQuotaService.preCheck(tenantContext, headers, routing.getProvider(), routing.getProviderModel(), request)
                    .doOnNext(quotaContext -> tracePublisher.publish(requestId, tenantContext, AiRequestTraceEvent.STAGE_QUOTA, "ok",
                            routing.getProvider(), routing.getProviderModel(),
                            "reservedTokens=" + quotaContext.getReservedTokens(), start, Boolean.FALSE))
                    .flatMap(quotaContext -> upstreamCallExecutor.callWithFallback(callContext)
                            .doOnSuccess(attemptResult -> {
                                String body = attemptResult.body();
                                UsageDetail usageDetail = usageExtractor.extractUsage(body);
                                Long totalTokens = usageDetail == null ? null : usageDetail.getTotalTokens();
                                if (totalTokens != null) {
                                    settleQuota(quotaContext, totalTokens, requestId);
                                } else {
                                    log.warn("usage missing from upstream response, quota stays at reserved value: requestId={}", requestId);
                                }
                                if (cacheEnabled) {
                                    writeCache(cacheKey, body, routing, request, tenantContext, requestId);
                                }
                                long tokenIn = usageDetail == null ? 0L : safeLong(usageDetail.getPromptTokens());
                                long tokenOut = usageDetail == null ? 0L : safeLong(usageDetail.getCompletionTokens());
                                long latencyMillis = Duration.between(start, Instant.now()).toMillis();
                                aiGatewayTracer.tag(span, "actual.provider", attemptResult.provider());
                                aiGatewayTracer.tag(span, "actual.model", attemptResult.providerModel());
                                tracePublisher.publish(requestId, tenantContext, AiRequestTraceEvent.STAGE_COMPLETED, "ok",
                                        attemptResult.provider(), attemptResult.providerModel(), null, start, Boolean.FALSE);
                                routingPlanResolver.recordOutcome(attemptResult.provider(), attemptResult.providerModel(),
                                        latencyMillis, true, tokenIn, tokenOut);
                                metricsRecorder.recordCall(AiCallRecord.builder()
                                        .requestId(requestId)
                                        .provider(attemptResult.provider())
                                        .model(attemptResult.providerModel())
                                        .tenantId(tenantContext.tenantId())
                                        .appId(tenantContext.appId())
                                        .keyId(tenantContext.keyId())
                                        .tokenIn(tokenIn)
                                        .tokenOut(tokenOut)
                                        .latencyMillis(latencyMillis)
                                        .status(200)
                                        .cacheHit(false)
                                        .build());
                                aiGatewayTracer.end(span);
                            })
                            .doOnError(ex -> {
                                releaseQuota(quotaContext, requestId);
                                tracePublisher.publish(requestId, tenantContext, AiRequestTraceEvent.STAGE_FAILED, "error",
                                        routing.getProvider(), routing.getProviderModel(), ex.getMessage(), start, Boolean.FALSE);
                                aiGatewayTracer.tag(span, "error", ex.getMessage());
                                aiGatewayTracer.endWithError(span, ex);
                                metricsRecorder.recordCall(AiCallRecord.builder()
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
                                        .build());
                            })
                            .map(AttemptResult::body));
        }));
    }

    /**
     * 精确缓存 + 语义缓存读取：命中则结束 span 并返回缓存体，未命中记录 miss 统计后返回空 Mono。
     * <p>
     * 精确缓存走响应式 Redis；语义缓存实现目前仍是同步客户端，因此放到弹性线程池执行，
     * 避免在 Netty 事件循环上阻塞。
     */
    private Mono<String> loadFromCache(String cacheKey, Span span, AiRoutingResult routing,
                                       AiChatCompletionReqDTO request, TenantContext tenantContext, String requestId) {
        return redisResponseCacheService.get(cacheKey)
                .flatMap(cached -> {
                    recordCacheHit(CacheHitKind.EXACT, span, routing, tenantContext, requestId);
                    return Mono.just(cached);
                })
                .switchIfEmpty(Mono.defer(() -> findSemanticCached(routing, request)
                        .flatMap(semanticCached -> {
                            recordCacheHit(CacheHitKind.SEMANTIC, span, routing, tenantContext, requestId);
                            return Mono.just(semanticCached);
                        })
                        .switchIfEmpty(Mono.defer(() -> {
                            tracePublisher.publish(requestId, tenantContext, AiRequestTraceEvent.STAGE_CACHE, "miss",
                                    routing.getProvider(), routing.getProviderModel(), null, null, Boolean.FALSE);
                            aiCacheStatsService.recordMiss();
                            metricsRecorder.recordTenantCacheEvent(tenantContext.tenantId(), "miss");
                            return Mono.empty();
                        }))));
    }

    /**
     * 缓存命中的统一记录点。
     * <p>
     * 精确命中与语义命中是同一类事实——这次请求没有打到上游，因此它必须在三处统计上给出一致的结论：
     * <ul>
     *   <li>{@link AiCacheStatsService} 快照：按命中类型分别计数，供 {@code /v1/cache/stats} 看进程内趋势；</li>
     *   <li>Redis 租户聚合：两类命中都计入 {@code cacheHit}；</li>
     *   <li>调用明细 {@link AiCallRecord}：账单导出与调用列表的数据源。</li>
     * </ul>
     * 语义命中原先漏写调用明细，于是出现"租户 cacheHit 记到了、调用列表里却找不到这次请求"，
     * 两个数字天然对不上。两类命中的写入收进这一个方法，就不会再各写一半。
     */
    private void recordCacheHit(CacheHitKind kind, Span span, AiRoutingResult routing,
                               TenantContext tenantContext, String requestId) {
        aiGatewayTracer.tag(span, "cache.hit", kind.tracerTag());
        tracePublisher.publish(requestId, tenantContext, AiRequestTraceEvent.STAGE_CACHE, "hit",
                routing.getProvider(), routing.getProviderModel(), kind.traceDetail(), null, Boolean.FALSE);
        if (kind == CacheHitKind.EXACT) {
            aiCacheStatsService.recordHit();
        } else {
            aiCacheStatsService.recordSemanticHit();
        }
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
                // 命中没走上游，"无上游耗时"记 0，不要与真实上游延迟混进同一个分布
                .latencyMillis(0L)
                .status(200)
                .cacheHit(true)
                .build());
        aiGatewayTracer.end(span);
    }

    /**
     * 缓存命中类型。两类命中在"有没有打到上游"上完全一样，只在统计归属与链路文案上不同。
     */
    private enum CacheHitKind {

        EXACT("true", "精确缓存命中"),
        SEMANTIC("semantic", "语义缓存命中");

        private final String tracerTag;

        private final String traceDetail;

        CacheHitKind(String tracerTag, String traceDetail) {
            this.tracerTag = tracerTag;
            this.traceDetail = traceDetail;
        }

        String tracerTag() {
            return tracerTag;
        }

        String traceDetail() {
            return traceDetail;
        }
    }

    private Mono<String> findSemanticCached(AiRoutingResult routing, AiChatCompletionReqDTO request) {
        return Mono.fromCallable(() -> semanticCacheService.find(routing.getProvider(), routing.getProviderModel(), request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optional -> optional.map(Mono::just).orElseGet(Mono::empty))
                .onErrorResume(ex -> {
                    log.warn("semantic cache lookup failed, fallback to upstream: {}", ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 写入响应缓存与语义缓存。
     * <p>
     * 缓存写失败只降级为"本次未缓存"，不能影响已经成功的响应。
     */
    private void writeCache(String cacheKey, String body, AiRoutingResult routing,
                            AiChatCompletionReqDTO request, TenantContext tenantContext, String requestId) {
        redisResponseCacheService.put(cacheKey, body, properties.getCache().getTtl())
                .subscribe(ignored -> {
                }, ex -> log.warn("failed to write response cache: requestId={}", requestId, ex));
        Mono.fromRunnable(() -> semanticCacheService.put(routing.getProvider(), routing.getProviderModel(), request, body))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(ignored -> {
                }, ex -> log.warn("failed to write semantic cache: requestId={}", requestId, ex));
        aiCacheStatsService.recordWrite();
        metricsRecorder.recordTenantCacheEvent(tenantContext.tenantId(), "write");
    }

    /**
     * 流式聊天补全链路（SSE）。
     * <p>
     * 与非流式链路共用路由与治理逻辑，差异在于：
     * <ul>
     *   <li>用量只能在 chunk 经过时增量收集，结束时按实际用量结算；</li>
     *   <li>流中途失败不能再改 HTTP 状态码，改为下发一个 OpenAI 兼容错误帧并补 [DONE]；</li>
     *   <li>客户端取消要退还预扣配额，否则断开的流会持续占用额度。</li>
     * </ul>
     */
    public Flux<String> streamChatCompletion(AiChatCompletionReqDTO request, HttpHeaders headers, TenantContext tenantContext) {
        Span span = aiGatewayTracer.startSpan("ai-gateway.stream-chat-completion");
        RoutingPlanResolver.RoutingPlan plan = routingPlanResolver.resolve(tenantContext, request.getModel(), headers);
        request.setModel(plan.effectiveModel());
        AiRoutingResult routing = plan.routing();
        String requestId = resolveRequestId(headers);
        aiGatewayTracer.tag(span, "request.id", requestId);
        aiGatewayTracer.tag(span, "provider", routing.getProvider());
        aiGatewayTracer.tag(span, "model", routing.getProviderModel());
        aiGatewayTracer.tag(span, "tenant.id", tenantContext.tenantId());
        aiGatewayTracer.tag(span, "stream", "true");
        tracePublisher.publish(requestId, tenantContext, AiRequestTraceEvent.STAGE_ROUTING, "ok",
                routing.getProvider(), routing.getProviderModel(), "routeSource=" + routing.getRouteSource(), null, Boolean.TRUE);
        HttpHeaders forwardHeaders = buildForwardHeaders(headers, requestId);
        List<RouteTarget> routeTargets = RouteTarget.from(routing);
        Instant start = Instant.now();
        StreamUsageCollector collector = new StreamUsageCollector(usageExtractor);
        AtomicReference<RouteTarget> servingRoute = new AtomicReference<>(routeTargets.get(0));
        UpstreamCallContext callContext = new UpstreamCallContext(
                routeTargets, request, forwardHeaders, requestId, tenantContext, start);

        return redisTokenQuotaService.preCheck(tenantContext, headers, routing.getProvider(), routing.getProviderModel(), request)
                .doOnNext(quotaContext -> tracePublisher.publish(requestId, tenantContext, AiRequestTraceEvent.STAGE_QUOTA, "ok",
                        routing.getProvider(), routing.getProviderModel(),
                        "reservedTokens=" + quotaContext.getReservedTokens(), start, Boolean.TRUE))
                .flatMapMany(quotaContext -> {
                    AtomicBoolean finalized = new AtomicBoolean(false);
                    AtomicBoolean firstTokenSeen = new AtomicBoolean(false);
                    return upstreamCallExecutor.callStreamWithFallback(callContext, collector, servingRoute)
                            .doOnNext(chunk -> {
                                if (firstTokenSeen.compareAndSet(false, true)) {
                                    tracePublisher.publish(requestId, tenantContext, AiRequestTraceEvent.STAGE_FIRST_TOKEN, "ok",
                                            servingRoute.get().provider(), servingRoute.get().providerModel(),
                                            null, start, Boolean.TRUE);
                                }
                            })
                            .doOnComplete(() -> finalizeStream(StreamOutcome.COMPLETED, finalized, quotaContext, collector,
                                    span, servingRoute.get(), tenantContext, requestId, start))
                            .doOnError(ex -> finalizeStream(StreamOutcome.FAILED, finalized, quotaContext, collector,
                                    span, servingRoute.get(), tenantContext, requestId, start))
                            .doOnCancel(() -> finalizeStream(StreamOutcome.CANCELLED, finalized, quotaContext, collector,
                                    span, servingRoute.get(), tenantContext, requestId, start))
                            .concatWith(Flux.defer(() -> collector.doneSeen() ? Flux.empty() : Flux.just(DONE_PAYLOAD)))
                            .onErrorResume(ex -> Flux.just(AiGatewayErrorMapper.toErrorFramePayload(ex), DONE_PAYLOAD));
                });
    }

    /**
     * 流式终态统一处理：结算用量、记录指标、收尾 span。
     * <p>
     * 三个终态回调理论上互斥，但取消与完成可能在竞态下同时触发，因此用 {@code finalized} 兜底。
     */
    private void finalizeStream(StreamOutcome outcome,
                                AtomicBoolean finalized,
                                QuotaPreCheckContext quotaContext,
                                StreamUsageCollector collector,
                                Span span,
                                RouteTarget servingRoute,
                                TenantContext tenantContext,
                                String requestId,
                                Instant start) {
        if (!finalized.compareAndSet(false, true)) {
            return;
        }
        UsageDetail usage = collector.usage();
        Long totalTokens = usage == null ? null : usage.getTotalTokens();
        if (totalTokens != null && totalTokens > 0) {
            settleQuota(quotaContext, totalTokens, requestId);
        } else if (outcome == StreamOutcome.COMPLETED) {
            log.warn("stream finished without usage, quota stays at reserved value: requestId={}", requestId);
        } else {
            releaseQuota(quotaContext, requestId);
        }

        Integer status = switch (outcome) {
            case COMPLETED -> 200;
            case CANCELLED -> 499;
            case FAILED -> 502;
        };
        long tokenIn = usage == null ? 0L : safeLong(usage.getPromptTokens());
        long tokenOut = usage == null ? 0L : safeLong(usage.getCompletionTokens());
        long latencyMillis = Duration.between(start, Instant.now()).toMillis();
        if (outcome == StreamOutcome.COMPLETED) {
            aiGatewayTracer.tag(span, "actual.provider", servingRoute.provider());
            aiGatewayTracer.tag(span, "actual.model", servingRoute.providerModel());
            aiGatewayTracer.end(span);
            routingPlanResolver.recordOutcome(servingRoute.provider(), servingRoute.providerModel(),
                    latencyMillis, true, tokenIn, tokenOut);
            tracePublisher.publish(requestId, tenantContext, AiRequestTraceEvent.STAGE_COMPLETED, "ok",
                    servingRoute.provider(), servingRoute.providerModel(), null, start, Boolean.TRUE);
        } else {
            aiGatewayTracer.tag(span, "stream.outcome", outcome.name().toLowerCase());
            // 客户端取消不等于上游故障，状态上要分得清
            tracePublisher.publish(requestId, tenantContext, AiRequestTraceEvent.STAGE_FAILED,
                    outcome == StreamOutcome.CANCELLED ? "cancelled" : "error",
                    servingRoute.provider(), servingRoute.providerModel(),
                    "stream " + outcome.name().toLowerCase(), start, Boolean.TRUE);
            aiGatewayTracer.endWithError(span, new IllegalStateException("stream " + outcome.name().toLowerCase()));
        }
        metricsRecorder.recordCall(AiCallRecord.builder()
                .requestId(requestId)
                .provider(servingRoute.provider())
                .model(servingRoute.providerModel())
                .tenantId(tenantContext.tenantId())
                .appId(tenantContext.appId())
                .keyId(tenantContext.keyId())
                .tokenIn(tokenIn)
                .tokenOut(tokenOut)
                .latencyMillis(latencyMillis)
                .status(status)
                .cacheHit(false)
                .build());
    }

    /**
     * 按实际用量结算配额，并把结算后的剩余量写进响应头快照。
     */
    private void settleQuota(QuotaPreCheckContext quotaContext, long actualTokens, String requestId) {
        redisTokenQuotaService.adjustByActualUsage(quotaContext, actualTokens)
                .doOnNext(settleResult -> {
                    if (settleResult == null) {
                        return;
                    }
                    rateLimitHeaderService.record(requestId, rateLimitHeaderService.buildTokenHeaders(
                            quotaContext.getMinuteQuota(),
                            settleResult.minuteUsed(),
                            quotaContext.getDayQuota(),
                            settleResult.dayUsed()));
                })
                .subscribe(ignored -> {
                }, ex -> log.warn("failed to settle token quota: requestId={}", requestId, ex));
    }

    private void releaseQuota(QuotaPreCheckContext quotaContext, String requestId) {
        redisTokenQuotaService.release(quotaContext)
                .subscribe(ignored -> {
                }, ex -> log.warn("failed to release reserved token quota: requestId={}", requestId, ex));
    }

    /**
     * 流式终态。
     */
    private enum StreamOutcome {
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * 组装透传到上游的请求头。
     * <p>
     * 只搬运白名单内的客户端头 + 链路 ID；上游凭证由 {@link UpstreamCallExecutor} 注入。
     */
    private HttpHeaders buildForwardHeaders(HttpHeaders source, String requestId) {
        HttpHeaders target = new HttpHeaders();
        target.set("X-Request-Id", requestId);
        for (String headerName : FORWARDED_CLIENT_HEADERS) {
            String value = source.getFirst(headerName);
            if (StringUtils.hasText(value)) {
                target.set(headerName, value);
            }
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
        return AiGatewayErrorMapper.statusOf(throwable);
    }

    /**
     * 安全处理可空 Long，避免空指针。
     */
    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }
}
