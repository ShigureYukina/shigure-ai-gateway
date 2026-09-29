package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.ProviderModelListReqDTO;
import com.nageoffer.shortlink.aigateway.sync.UpstreamModelProbe;
import com.nageoffer.shortlink.aigateway.upstream.OutboundUrlValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制台上的"测试连接"入口：用还没保存的 baseUrl + Key 拉一次模型列表。
 * <p>
 * 与定时同步的区别只有凭证来源（这里是请求体里的 Key，同步任务用渠道里已存的凭证），
 * 请求构造与响应解析统一走 {@link UpstreamModelProbe}，避免同一个上游在两个入口给出不同结果。
 * <p>
 * 这个端点会让网关朝<b>用户指定的任意地址</b>发请求，所以它同时是本项目最直接的 SSRF 入口：
 * 地址必须先过 {@link OutboundUrlValidator#requireSafe}。校验要解析 DNS，
 * 因此在 {@code boundedElastic} 上执行 —— 不能占用 event loop。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/providers")
@Tag(name = "模型发现", description = "通过 API 地址与 Key 获取模型列表")
public class AiProviderController {

    private final UpstreamModelProbe upstreamModelProbe;

    private final AiGatewayProperties properties;

    @Operation(summary = "获取模型列表", description = "调用上游 /v1/models 并返回模型 ID 列表")
    @PostMapping("/models")
    public Mono<ResponseEntity<Map<String, Object>>> listModels(@Valid @RequestBody ProviderModelListReqDTO requestParam) {
        boolean allowPrivateAddresses = properties.getSecurity().getSsrf().isAllowPrivateAddresses();
        // defer：校验里的异常要在订阅时才抛出、由下面的分支翻译成 400，
        // 而不是构建 Mono 的时候直接炸出方法体（那样连响应体形状都保不住）。
        // subscribeOn(boundedElastic)：requireSafe 要解析 DNS，是会阻塞的，不能占用 event loop。
        return Mono.defer(() -> {
            String normalizedBaseUrl;
            try {
                normalizedBaseUrl = OutboundUrlValidator.requireSafe(
                        requestParam.getBaseUrl(), allowPrivateAddresses);
            } catch (IllegalArgumentException ex) {
                return badRequest(ex.getMessage());
            }
            return upstreamModelProbe.probeWithExplicitKey(normalizedBaseUrl, requestParam.getApiKey())
                    .map(models -> ResponseEntity.ok(toSuccessResponse(models)))
                    .onErrorResume(WebClientResponseException.class, ex -> Mono.just(
                            ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                                    // 上游响应体不回传给浏览器：它是另一个系统的内容，
                                    // 原样透出等于把"用控制台读任意上游响应"变成一个数据外带通道
                                    "message", "上游响应错误: " + ex.getStatusCode().value(),
                                    "models", List.of()
                            ))
                    ))
                    .onErrorResume(ex -> Mono.just(
                            ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                                    "message", "调用上游失败: " + ex.getMessage(),
                                    "models", List.of()
                            ))
                    ));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 地址不合法的统一响应。与 200 分支保持同一形状（{@code models} 恒存在），
     * 控制台据此可以无分支地渲染"连接失败"。
     */
    private Mono<ResponseEntity<Map<String, Object>>> badRequest(String message) {
        return Mono.just(ResponseEntity.badRequest().body(Map.of(
                "message", message,
                "models", List.of()
        )));
    }

    private Map<String, Object> toSuccessResponse(List<String> models) {
        Map<String, Object> body = new HashMap<>();
        body.put("models", models);
        body.put("count", models.size());
        return body;
    }
}
