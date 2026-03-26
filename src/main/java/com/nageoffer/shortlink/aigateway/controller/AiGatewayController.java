package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.security.ApiKeyAuthService;
import com.nageoffer.shortlink.aigateway.service.AiGatewayService;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1")
@Tag(name = "AI 网关入口", description = "OpenAI 兼容入口与转发")
/**
 * 网关统一对外入口。
 * <p>
 * 负责接收 OpenAI 兼容请求，并根据 stream 参数分发到
 * 非流式（一次性 JSON）或流式（SSE）处理链路。
 */
public class AiGatewayController {

    private final AiGatewayService aiGatewayService;

    private final ApiKeyAuthService apiKeyAuthService;

    public AiGatewayController(AiGatewayService aiGatewayService, ApiKeyAuthService apiKeyAuthService) {
        this.aiGatewayService = aiGatewayService;
        this.apiKeyAuthService = apiKeyAuthService;
    }

    @Operation(summary = "聊天补全", description = "OpenAI 兼容 /v1/chat/completions，支持非流式与 SSE")
    @PostMapping("/chat/completions")
    /**
     * 聊天补全统一入口。
     *
     * @param requestParam 客户端请求体
     * @param exchange WebFlux 交换对象（用于读取透传请求头）
     * @return stream=true 时返回 SSE；否则返回标准 JSON
     */
    public Mono<ResponseEntity<Object>> chatCompletions(@Valid @RequestBody AiChatCompletionReqDTO requestParam,
                                                         ServerWebExchange exchange) {
        TenantContext tenantContext = apiKeyAuthService.authenticate(exchange.getRequest().getHeaders());
        if (Boolean.TRUE.equals(requestParam.getStream())) {
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(aiGatewayService.streamChatCompletion(requestParam, exchange.getRequest().getHeaders(), tenantContext)));
        }
        return aiGatewayService.chatCompletion(requestParam, exchange.getRequest().getHeaders(), tenantContext)
                .map(result -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body((Object) result));
    }
}
