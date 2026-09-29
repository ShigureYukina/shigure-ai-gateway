package com.nageoffer.shortlink.aigateway.sync;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.governance.UpstreamCredentialService;
import com.nageoffer.shortlink.aigateway.probe.ProbeErrorType;
import com.nageoffer.shortlink.aigateway.probe.ProbeOutcome;
import com.nageoffer.shortlink.aigateway.upstream.OutboundUrlValidator;
import com.nageoffer.shortlink.aigateway.upstream.UpstreamUrlSupport;
import io.netty.handler.timeout.ReadTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * 上游模型端点探测。
 * <p>
 * 全项目只有这一处构造"列出模型"的请求：URL 拼装、鉴权头、超时、响应解析都在这里定义一次。
 * 之前定时同步与控制台"测试连接"各写了一遍，规则不同（一个有超时一个没有、一个复用解析器一个自己遍历 JSON），
 * 于是同一个上游在两个入口会给出不同结果。
 * <p>
 * 差异只剩一处，且刻意保留：<b>凭证来源</b>。同步任务用渠道里已经存好的凭证（含 Key 池轮换、
 * 自定义鉴权头），控制台测的是请求体里还没入库的 baseUrl + Key，不能共用。
 * <p>
 * 失败表示方式也分两种，由调用方决定：同步任务降级为空清单（一个渠道抖动不该打断整批同步），
 * 控制台让异常抛出（需要把上游状态码原样转给用户）。
 */
@Slf4j
@Component
public class UpstreamModelProbe {

    private final AiGatewayProperties properties;

    private final WebClient metadataWebClient;

    private final UpstreamMetadataParser parser;

    private final UpstreamCredentialService upstreamCredentialService;

    /**
     * 显式构造器（不再用 {@code @RequiredArgsConstructor}）：需要给 WebClient 加
     * {@code @Qualifier} 指定元数据专用客户端（响应体上限 2MB，见 {@code WebClientConfiguration}）。
     * 参数个数与顺序不变，调用方零改动。
     */
    public UpstreamModelProbe(AiGatewayProperties properties,
                              @Qualifier("aiMetadataWebClient") WebClient metadataWebClient,
                              UpstreamMetadataParser parser,
                              UpstreamCredentialService upstreamCredentialService) {
        this.properties = properties;
        this.metadataWebClient = metadataWebClient;
        this.parser = parser;
        this.upstreamCredentialService = upstreamCredentialService;
    }

    /**
     * 按渠道已保存的凭证探测。失败降级为空清单，只记日志。
     * <p>
     * 旧契约保持不变（{@code ModelCatalogSyncService} 依赖它）：一个渠道抖动不该打断整批同步，
     * 也不该把上一份清单清空。
     */
    public Mono<List<String>> probeWithStoredCredential(String provider) {
        return probeWithDiagnosis(provider)
                .doOnNext(outcome -> {
                    if (outcome.errorType() == ProbeErrorType.NO_CREDENTIAL) {
                        // 没配 Key 的渠道问了必然 401，属于预期情况，不必当成失败惊动调用方
                        log.debug("skip model discovery for {}: {}", provider, outcome.message());
                    } else if (!outcome.ok()) {
                        log.warn("failed to discover models from {}: {} ({})",
                                provider, outcome.errorType(), outcome.message());
                    }
                })
                .map(ProbeOutcome::models);
    }

    /**
     * 诊断用探测：区分宕机 / 401 / 限流 / 超时 / 没模型 / 没配 Key。<b>绝不抛异常。</b>
     * <p>
     * 与 {@link #probeWithStoredCredential} 的区别只有失败表示方式：那边把失败压成空清单，
     * 这边把失败当成一等结果返回，因为主动探测需要拿这个分类去决定
     * "这次失败该不该计入禁用计数"（限流不该，见 {@link ProbeErrorType}）。
     */
    public Mono<ProbeOutcome> probeWithDiagnosis(String provider) {
        long startedAt = System.currentTimeMillis();
        String baseUrl = properties.getUpstream().getProviderBaseUrl().get(provider);
        if (!StringUtils.hasText(baseUrl)) {
            // 连请求都构不出来。渠道清单本来就按 baseUrl 非空筛选，走到这里是直接调用方的问题
            return Mono.just(ProbeOutcome.failure(provider, ProbeErrorType.CONNECT_FAILED,
                    "未配置 baseUrl", elapsed(startedAt)));
        }
        HttpHeaders headers = new HttpHeaders();
        try {
            upstreamCredentialService.applyCredential(headers, provider, null);
        } catch (AiGatewayClientException ex) {
            // 凭证缺失是配置问题（动作：去补 Key），与"Key 失效"（401，动作：换 Key）必须分开报
            return Mono.just(ProbeOutcome.failure(provider, ProbeErrorType.NO_CREDENTIAL,
                    ex.getMessage(), elapsed(startedAt)));
        }
        return fetch(baseUrl, headers)
                .map(models -> models.isEmpty()
                        // 200 但没有模型不算成功：与 ModelCatalogSyncService"空结果保留旧快照"的口径一致
                        ? ProbeOutcome.failure(provider, ProbeErrorType.BAD_RESPONSE,
                                "上游未返回任何模型", elapsed(startedAt))
                        : ProbeOutcome.success(provider, models, elapsed(startedAt)))
                .onErrorResume(ex -> Mono.just(classify(provider, ex, elapsed(startedAt))));
    }

    /**
     * 按显式 baseUrl + Key 探测。Key 尚未入库，因此不能走 {@link UpstreamCredentialService}；
     * 失败原样抛出，异常类型与上游状态码都留给调用方决定怎么呈现。
     */
    public Mono<List<String>> probeWithExplicitKey(String baseUrl, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        return fetch(baseUrl, headers);
    }

    private static ProbeOutcome classify(String provider, Throwable ex, long latencyMillis) {
        if (ex instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            ProbeErrorType type = switch (status) {
                case 401 -> ProbeErrorType.UNAUTHORIZED;
                case 403 -> ProbeErrorType.FORBIDDEN;
                case 429 -> ProbeErrorType.RATE_LIMITED;
                default -> status >= 500 ? ProbeErrorType.UPSTREAM_5XX : ProbeErrorType.BAD_RESPONSE;
            };
            return ProbeOutcome.failure(provider, type, "上游返回 HTTP " + status, latencyMillis, status);
        }
        // 超时会以 TimeoutException（Reactor 的 timeout 算子）或 Netty 的 ReadTimeoutException
        // （连接读空闲 / responseTimeout）出现，两种都要认
        if (ex instanceof TimeoutException || hasCause(ex, ReadTimeoutException.class)) {
            return ProbeOutcome.failure(provider, ProbeErrorType.TIMEOUT,
                    "请求超时：" + ex.getMessage(), latencyMillis);
        }
        if (hasCause(ex, ConnectException.class) || hasCause(ex, UnknownHostException.class)
                || hasCause(ex, SSLException.class)) {
            return ProbeOutcome.failure(provider, ProbeErrorType.CONNECT_FAILED,
                    "连接失败：" + ex.getMessage(), latencyMillis);
        }
        // 兜底归到 BAD_RESPONSE 而不是 OK：解析不了（含响应体超过 2MB 上限）就是"渠道不可用"，
        // fail-closed 才不会把坏渠道留在候选集里
        return ProbeOutcome.failure(provider, ProbeErrorType.BAD_RESPONSE,
                ex.getClass().getSimpleName() + ": " + ex.getMessage(), latencyMillis);
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        Throwable current = ex;
        // 限深：正常异常链不会很长，防的是畸形链导致的死循环
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static long elapsed(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    private Mono<List<String>> fetch(String baseUrl, HttpHeaders headers) {
        // 惰性化：requireWellFormed 抛的是同步异常，若在方法体里直接抛就会绕过下面
        // probeWithStoredCredential 的 onErrorResume，"一个渠道配置写错 → 整批同步中断"。
        // 放进 defer 之后，异常变成 onError 信号，降级语义与原设计一致。
        return Mono.defer(() -> {
            String uri = UpstreamUrlSupport.join(baseUrl, properties.getSync().getModel().getPath());
            // 与 ProviderRoutingService.buildChatUri 同一层：只做不碰 DNS 的廉价校验
            // （baseUrl 来自 yml，但 model-path 也会参与拼接，所以校验拼好的地址）
            OutboundUrlValidator.requireWellFormed(uri);
            return metadataWebClient.get()
                    .uri(uri)
                    .headers(target -> target.addAll(headers))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(properties.getSync().getTimeout())
                    .map(parser::parseModelIds)
                    .doOnNext(models -> log.debug("discovered {} models from {}", models.size(), uri));
        });
    }
}
