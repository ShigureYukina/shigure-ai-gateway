package com.nageoffer.shortlink.aigateway.service;

import com.nageoffer.shortlink.aigateway.adapter.ProviderAdapter;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.model.AiCanonicalChatRequest;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayUpstreamException;
import com.nageoffer.shortlink.aigateway.governance.AiSafetyGuard;
import com.nageoffer.shortlink.aigateway.governance.ProviderRateLimitService;
import com.nageoffer.shortlink.aigateway.governance.StreamUsageCollector;
import com.nageoffer.shortlink.aigateway.governance.UpstreamCredentialService;
import com.nageoffer.shortlink.aigateway.observability.AiRequestTraceEvent;
import com.nageoffer.shortlink.aigateway.observability.RequestTracePublisher;
import com.nageoffer.shortlink.aigateway.plugin.PluginChainService;
import com.nageoffer.shortlink.aigateway.plugin.PluginRequestContext;
import com.nageoffer.shortlink.aigateway.plugin.PluginResponseContext;
import com.nageoffer.shortlink.aigateway.routing.AiRoutePolicy;
import com.nageoffer.shortlink.aigateway.routing.ProviderRoutingService;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * 上游调用执行器：把"请求真正打出去"这件事从编排里分离出来。
 * <p>
 * 负责一件事——给定一组路由目标，按序尝试直到有一个成功，并且把这条链路上的机制都收在这里：
 * <ul>
 *   <li>通道级 RPM 闸门（超限视为通道故障，可回退；不占用也不污染其他通道）；</li>
 *   <li>凭证注入与 Key 池回灌（成功/失败都按具体这把 Key 归因）；</li>
 *   <li>协议适配（请求/响应/SSE 三向转换）、插件前后置、输出安全过滤；</li>
 *   <li>熔断、重试、超时的装配；</li>
 *   <li>失败归因：只有"这条通道本身不行"才回退并计入健康度。</li>
 * </ul>
 * 用量结算、缓存、指标记账仍留在编排侧——那些是"这一次请求怎么结账"，与"怎么把请求打出去"不同层。
 * <p>
 * 非流式与流式刻意放在同一个类里：两者共享上面全部机制，差异只在 {@code exchangeToMono} 与
 * {@code exchangeToFlux}、以及成功终态是 {@code doOnNext} 还是 {@code doOnComplete}。
 * 拆成两个类会让重试/熔断/超时的装配出现第二份拷贝，而这份拷贝一旦不同步就是线上"只有流式会重试"这类事故。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpstreamCallExecutor {

    /**
     * 回传给客户端的上游错误体长度上限，避免把上游整段响应塞进错误消息与日志。
     */
    private static final int UPSTREAM_ERROR_BODY_LIMIT = 512;

    private final WebClient aiGatewayWebClient;

    private final List<ProviderAdapter> providerAdapters;

    private final ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory;

    private final UpstreamCredentialService upstreamCredentialService;

    private final PluginChainService pluginChainService;

    private final AiSafetyGuard aiSafetyGuard;

    private final ProviderRateLimitService providerRateLimitService;

    private final ProviderRoutingService providerRoutingService;

    private final AiGatewayProperties properties;

    private final RequestTracePublisher tracePublisher;

    /**
     * 非流式调用：返回首个成功目标的结果。
     */
    public Mono<AttemptResult> callWithFallback(UpstreamCallContext context) {
        return callWithFallback(context, 0);
    }

    private Mono<AttemptResult> callWithFallback(UpstreamCallContext context, int index) {
        RouteTarget routeTarget = context.target(index);
        return Mono.defer(() -> {
                    publishStage(context, routeTarget, AiRequestTraceEvent.STAGE_UPSTREAM, "ok",
                            routeTarget.upstreamUri(), Boolean.FALSE);
                    return providerRateLimitService.tryAcquire(routeTarget.provider())
                            .flatMap(allowed -> allowed
                                    ? forwardMono(context, routeTarget)
                                    : Mono.error(rateLimited(routeTarget)));
                })
                .onErrorResume(ex -> {
                    boolean channelFault = isProviderChannelFault(ex);
                    if (channelFault) {
                        providerRoutingService.recordProviderOutcome(routeTarget.provider(), routeTarget.providerModel(),
                                Duration.between(context.start(), Instant.now()).toMillis(), false, 0L, 0L);
                    }
                    if (channelFault && context.hasNext(index)) {
                        publishStage(context, routeTarget, AiRequestTraceEvent.STAGE_FALLBACK, "error",
                                ex.getMessage(), Boolean.FALSE);
                        log.warn("route failed, fallback to next provider: requestId={}, provider={}, reason={}",
                                context.requestId(), routeTarget.provider(), ex.getMessage());
                        return callWithFallback(context, index + 1);
                    }
                    return Mono.error(ex);
                });
    }

    /**
     * 流式调用：与 {@link #callWithFallback} 同一套回退规则，只是终态是流。
     *
     * @param servingRoute 记录实际服务的那个目标，供调用方在终态结算时归因（回退后与主路由不同）
     */
    public Flux<String> callStreamWithFallback(UpstreamCallContext context,
                                               StreamUsageCollector collector,
                                               AtomicReference<RouteTarget> servingRoute) {
        return callStreamWithFallback(context, 0, collector, servingRoute);
    }

    private Flux<String> callStreamWithFallback(UpstreamCallContext context,
                                                int index,
                                                StreamUsageCollector collector,
                                                AtomicReference<RouteTarget> servingRoute) {
        RouteTarget routeTarget = context.target(index);
        return Flux.defer(() -> {
                    servingRoute.set(routeTarget);
                    publishStage(context, routeTarget, AiRequestTraceEvent.STAGE_UPSTREAM, "ok",
                            routeTarget.upstreamUri(), Boolean.TRUE);
                    return Flux.from(providerRateLimitService.tryAcquire(routeTarget.provider()))
                            .flatMap(allowed -> allowed
                                    ? forwardStream(context, routeTarget, collector)
                                    : Flux.error(rateLimited(routeTarget)));
                })
                .onErrorResume(ex -> {
                    boolean channelFault = isProviderChannelFault(ex);
                    if (channelFault) {
                        providerRoutingService.recordProviderOutcome(routeTarget.provider(), routeTarget.providerModel(),
                                Duration.between(context.start(), Instant.now()).toMillis(), false, 0L, 0L);
                    }
                    if (channelFault && context.hasNext(index)) {
                        publishStage(context, routeTarget, AiRequestTraceEvent.STAGE_FALLBACK, "error",
                                ex.getMessage(), Boolean.TRUE);
                        log.warn("stream route failed, fallback to next provider: requestId={}, provider={}, reason={}",
                                context.requestId(), routeTarget.provider(), ex.getMessage());
                        return callStreamWithFallback(context, index + 1, collector, servingRoute);
                    }
                    return Flux.error(ex);
                });
    }

    /**
     * 真正把非流式请求打到上游：注入凭证、转换协议，并把结果回灌 Key 池。
     */
    private Mono<AttemptResult> forwardMono(UpstreamCallContext context, RouteTarget routeTarget) {
        ProviderAdapter adapter = resolveAdapter(routeTarget.provider());
        AiCanonicalChatRequest canonicalRequest = buildCanonicalRequest(context.request(), routeTarget.provider(),
                routeTarget.providerModel());
        pluginChainService.executeBeforeRequest(PluginRequestContext.builder()
                .provider(routeTarget.provider())
                .model(routeTarget.providerModel())
                .requestId(context.requestId())
                .request(context.request())
                .headers(context.forwardHeaders())
                .build());
        Object upstreamRequestBody = adapter.toUpstreamRequest(canonicalRequest);
        UpstreamCall upstreamCall = buildUpstreamCall(context.forwardHeaders(), routeTarget.provider(),
                context.tenantContext());
        return aiGatewayWebClient.post()
                .uri(routeTarget.upstreamUri())
                .headers(httpHeaders -> httpHeaders.addAll(upstreamCall.headers()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(upstreamRequestBody)
                .exchangeToMono(response -> handleUpstreamMonoResponse(response.statusCode(),
                        response.bodyToMono(String.class).defaultIfEmpty(""), routeTarget.routePolicy()))
                .transform(source -> circuitBreaker(routeTarget.provider()).run(source, Mono::error))
                .retryWhen(createRetry(routeTarget.routePolicy()))
                .timeout(resolveTimeout(routeTarget.routePolicy()))
                .flatMap(upstreamBody -> adapter.fromUpstreamResponse(upstreamBody, canonicalRequest))
                .map(responseBody -> pluginChainService.executeAfterResponse(PluginResponseContext.builder()
                        .provider(routeTarget.provider())
                        .model(routeTarget.providerModel())
                        .requestId(context.requestId())
                        .responseBody(responseBody)
                        .latencyMillis(Duration.between(context.start(), Instant.now()).toMillis())
                        .build()))
                .map(aiSafetyGuard::processOutput)
                .doOnNext(body -> upstreamCredentialService.reportOutcome(upstreamCall.lease(), true, null))
                .doOnError(ex -> upstreamCredentialService.reportOutcome(upstreamCall.lease(), false, ex))
                .map(responseBody -> new AttemptResult(routeTarget.provider(), routeTarget.providerModel(), responseBody));
    }

    /**
     * 流式版本：成功以流正常结束为准，失败按具体异常归因到这把 Key。
     */
    private Flux<String> forwardStream(UpstreamCallContext context, RouteTarget routeTarget,
                                       StreamUsageCollector collector) {
        ProviderAdapter adapter = resolveAdapter(routeTarget.provider());
        AiCanonicalChatRequest canonicalRequest = buildCanonicalRequest(context.request(), routeTarget.provider(),
                routeTarget.providerModel());
        pluginChainService.executeBeforeRequest(PluginRequestContext.builder()
                .provider(routeTarget.provider())
                .model(routeTarget.providerModel())
                .requestId(context.requestId())
                .request(context.request())
                .headers(context.forwardHeaders())
                .build());
        Object upstreamRequestBody = adapter.toUpstreamRequest(canonicalRequest);
        UpstreamCall upstreamCall = buildUpstreamCall(context.forwardHeaders(), routeTarget.provider(),
                context.tenantContext());
        return aiGatewayWebClient.post()
                .uri(routeTarget.upstreamUri())
                .headers(httpHeaders -> httpHeaders.addAll(upstreamCall.headers()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(upstreamRequestBody)
                .exchangeToFlux(response -> handleUpstreamFluxResponse(response.statusCode(),
                        response.bodyToFlux(String.class), response.bodyToMono(String.class).defaultIfEmpty(""),
                        routeTarget.routePolicy()))
                .transform(source -> circuitBreaker(routeTarget.provider()).run(source, Flux::error))
                .retryWhen(createRetry(routeTarget.routePolicy()))
                .timeout(resolveTimeout(routeTarget.routePolicy()))
                .transform(upstreamFlux -> adapter.fromUpstreamSse(upstreamFlux, canonicalRequest))
                .doOnNext(collector::accept)
                .map(responseBody -> pluginChainService.executeAfterResponse(PluginResponseContext.builder()
                        .provider(routeTarget.provider())
                        .model(routeTarget.providerModel())
                        .requestId(context.requestId())
                        .responseBody(responseBody)
                        .latencyMillis(Duration.between(context.start(), Instant.now()).toMillis())
                        .build()))
                .map(aiSafetyGuard::processOutput)
                .doOnComplete(() -> upstreamCredentialService.reportOutcome(upstreamCall.lease(), true, null))
                .doOnError(ex -> upstreamCredentialService.reportOutcome(upstreamCall.lease(), false, ex));
    }

    private void publishStage(UpstreamCallContext context, RouteTarget routeTarget, String stage, String status,
                              String detail, Boolean stream) {
        tracePublisher.publish(context.requestId(), context.tenantContext(), stage, status,
                routeTarget.provider(), routeTarget.providerModel(), detail, context.start(), stream);
    }

    private AiGatewayClientException rateLimited(RouteTarget routeTarget) {
        return new AiGatewayClientException(AiGatewayErrorCode.PROVIDER_RATE_LIMITED,
                "上游渠道已达调用频率上限: " + routeTarget.provider());
    }

    /**
     * 一次上游调用用到的转发头与实际选中的 Key 租约。
     */
    private record UpstreamCall(HttpHeaders headers, UpstreamCredentialService.CredentialLease lease) {
    }

    /**
     * 失败是否归因于该 provider 通道自身。
     * <p>
     * 调用方自身的问题（参数/鉴权/配额）换 provider 也无济于事；
     * 网关配置类问题（缺凭证、缺适配器）恰恰应该换一条可用通道。
     * 该判据同时服务两件事：是否回退、是否计入 provider 健康度——
     * 两者都只在"这条通道本身不行"时才成立。
     */
    private boolean isProviderChannelFault(Throwable throwable) {
        if (throwable instanceof AiGatewayClientException clientException) {
            return switch (clientException.getErrorCode()) {
                case UPSTREAM_CREDENTIAL_MISSING, PROVIDER_NOT_CONFIGURED, PROVIDER_ADAPTER_NOT_FOUND, UPSTREAM_RETRY_EXHAUSTED,
                     PROVIDER_RATE_LIMITED -> true;
                default -> false;
            };
        }
        return true;
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
        return new AiGatewayUpstreamException(statusCode, "上游响应异常: status=" + statusCode + ", body=" + truncate(body), retriable);
    }

    private String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= UPSTREAM_ERROR_BODY_LIMIT ? body : body.substring(0, UPSTREAM_ERROR_BODY_LIMIT) + "...(truncated)";
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
     * <p>
     * 消息里的未知字段与请求级扩展字段一并带走，保证 tools / response_format 等
     * 未显式建模的能力不会被网关吃掉；顺带在转换时做输入侧安全校验，
     * 确保"进上游之前"一定过检。
     */
    private AiCanonicalChatRequest buildCanonicalRequest(AiChatCompletionReqDTO request, String provider, String providerModel) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (AiChatCompletionMessage message : request.getMessages()) {
            aiSafetyGuard.verifyInput(message.getContent());
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("role", message.getRole());
            if (message.getContent() != null) {
                normalized.put("content", message.getContent());
            }
            if (message.getUnmapped() != null) {
                normalized.putAll(message.getUnmapped());
            }
            messages.add(normalized);
        }
        return AiCanonicalChatRequest.builder()
                .provider(provider)
                .clientModel(request.getModel())
                .providerModel(providerModel)
                .stream(Boolean.TRUE.equals(request.getStream()))
                .temperature(request.getTemperature())
                .maxTokens(request.getMaxTokens())
                .messages(messages)
                .metadata(request.getMetadata())
                .extra(request.getUnmapped())
                .build();
    }

    /**
     * 在转发头基础上注入该 provider 的上游凭证。
     */
    private UpstreamCall buildUpstreamCall(HttpHeaders forwardHeaders, String provider, TenantContext tenantContext) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(forwardHeaders);
        UpstreamCredentialService.CredentialLease lease =
                upstreamCredentialService.applyCredential(headers, provider, tenantContext.tenantId());
        return new UpstreamCall(headers, lease);
    }

    private ReactiveCircuitBreaker circuitBreaker(String provider) {
        return circuitBreakerFactory.create("provider-" + provider);
    }
}
