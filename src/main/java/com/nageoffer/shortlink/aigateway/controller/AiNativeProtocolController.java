package com.nageoffer.shortlink.aigateway.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorMapper;
import com.nageoffer.shortlink.aigateway.protocol.anthropic.AnthropicMessagesMapper;
import com.nageoffer.shortlink.aigateway.protocol.anthropic.AnthropicStreamMapper;
import com.nageoffer.shortlink.aigateway.protocol.responses.OpenAiResponsesMapper;
import com.nageoffer.shortlink.aigateway.protocol.responses.ResponsesStreamMapper;
import com.nageoffer.shortlink.aigateway.security.ApiKeyAuthService;
import com.nageoffer.shortlink.aigateway.service.AiGatewayService;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原生协议入口：客户端可以用 Anthropic SDK 或 Responses API 直连网关，
 * 背后复用同一条 chat 链路，因此路由、配额、缓存、安全、可观测一个都不少。
 * <p>
 * 三件事刻意做对：
 * <ol>
 *   <li>错误体按各自协议返回——Anthropic 客户端只认 {@code {"type":"error","error":{...}}}，
 *       把 OpenAI 形状的错误丢给它等于所有异常都变成"解析失败"；</li>
 *   <li>请求体先转成内部 DTO 再进治理链路，因此计费与配额口径与 OpenAI 入口完全一致；</li>
 *   <li>整个处理包在 {@code Mono.defer} 里，鉴权、参数校验、上游失败都走同一个错误出口，
 *       不会出现"有的错误是 Anthropic 形状、有的是默认形状"。</li>
 * </ol>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1")
@Tag(name = "原生协议入口", description = "Anthropic Messages 与 OpenAI Responses 协议入口")
public class AiNativeProtocolController {

    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final AiGatewayService aiGatewayService;

    private final ApiKeyAuthService apiKeyAuthService;

    private final AnthropicMessagesMapper anthropicMessagesMapper;

    private final AnthropicStreamMapper anthropicStreamMapper;

    private final OpenAiResponsesMapper openAiResponsesMapper;

    private final ResponsesStreamMapper responsesStreamMapper;

    @Operation(summary = "Anthropic Messages",
            description = "Claude 原生 /v1/messages。支持 system、文本/图片、tool_use/tool_result、tools、stop_sequences；"
                    + "max_tokens 缺省按 1024 处理，stream=true 时返回 Anthropic 事件流")
    @PostMapping("/messages")
    public Mono<ResponseEntity<Object>> messages(@RequestBody(required = false) String rawBody, ServerWebExchange exchange) {
        return Mono.defer(() -> {
            TenantContext tenantContext = apiKeyAuthService.authenticate(exchange.getRequest().getHeaders());
            AiChatCompletionReqDTO request = anthropicMessagesMapper.toChatRequest(parse(rawBody));
            validate(request);
            if (request.getMaxTokens() == null) {
                request.setMaxTokens(DEFAULT_MAX_TOKENS);
            }
            String clientModel = request.getModel();
            if (Boolean.TRUE.equals(request.getStream())) {
                Flux<ServerSentEvent<String>> stream = anthropicStreamMapper.toEventStream(
                        aiGatewayService.streamChatCompletion(request, exchange.getRequest().getHeaders(), tenantContext),
                        clientModel);
                return Mono.just(ResponseEntity.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body((Object) stream));
            }
            return aiGatewayService.chatCompletion(request, exchange.getRequest().getHeaders(), tenantContext)
                    .map(openAiBody -> ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body((Object) anthropicMessagesMapper.toMessageResponse(openAiBody, clientModel)));
        }).onErrorResume(ex -> Mono.just(anthropicError(ex)));
    }

    @Operation(summary = "Anthropic 输入 token 估算",
            description = "Claude 客户端的 /v1/messages/count_tokens。按与内部限流一致的 4 字符 1 token 口径估算")
    @PostMapping("/messages/count_tokens")
    public Mono<ResponseEntity<Object>> countTokens(@RequestBody(required = false) String rawBody, ServerWebExchange exchange) {
        return Mono.defer(() -> {
            apiKeyAuthService.authenticate(exchange.getRequest().getHeaders());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("input_tokens", anthropicMessagesMapper.countInputTokens(parse(rawBody)));
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body((Object) result));
        }).onErrorResume(ex -> Mono.just(anthropicError(ex)));
    }

    @Operation(summary = "OpenAI Responses",
            description = "Responses API 入口。支持 input（字符串/条目数组）、instructions、max_output_tokens、"
                    + "function 工具与 function_call_output 回填；不支持 previous_response_id、内置工具与 reasoning 条目")
    @PostMapping("/responses")
    public Mono<ResponseEntity<Object>> responses(@RequestBody(required = false) String rawBody, ServerWebExchange exchange) {
        return Mono.defer(() -> {
            TenantContext tenantContext = apiKeyAuthService.authenticate(exchange.getRequest().getHeaders());
            AiChatCompletionReqDTO request = openAiResponsesMapper.toChatRequest(parse(rawBody));
            validate(request);
            String clientModel = request.getModel();
            if (Boolean.TRUE.equals(request.getStream())) {
                Flux<ServerSentEvent<String>> stream = responsesStreamMapper.toEventStream(
                        aiGatewayService.streamChatCompletion(request, exchange.getRequest().getHeaders(), tenantContext),
                        clientModel);
                return Mono.just(ResponseEntity.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body((Object) stream));
            }
            return aiGatewayService.chatCompletion(request, exchange.getRequest().getHeaders(), tenantContext)
                    .map(openAiBody -> ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body((Object) openAiResponsesMapper.toResponse(openAiBody, clientModel)));
        }).onErrorResume(ex -> Mono.just(openAiError(ex)));
    }

    private JSONObject parse(String rawBody) {
        if (!StringUtils.hasText(rawBody)) {
            throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        try {
            JSONObject body = JSON.parseObject(rawBody);
            if (body == null || body.isEmpty()) {
                throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, "请求体不能为空");
            }
            return body;
        } catch (AiGatewayClientException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, "请求体不是合法 JSON");
        }
    }

    private void validate(AiChatCompletionReqDTO request) {
        if (!StringUtils.hasText(request.getModel())) {
            throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, "model 不能为空");
        }
        List<?> messages = request.getMessages();
        if (messages == null || messages.isEmpty()) {
            throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, "messages 不能为空");
        }
    }

    /**
     * Anthropic 错误形状：{@code {"type":"error","error":{"type","message"}}}。
     */
    private ResponseEntity<Object> anthropicError(Throwable ex) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", anthropicErrorType(ex));
        error.put("message", messageOf(ex));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", error);
        return ResponseEntity.status(AiGatewayErrorMapper.statusOf(ex))
                .contentType(MediaType.APPLICATION_JSON)
                .body((Object) body);
    }

    /**
     * Responses 与 Chat Completions 共用 OpenAI 的错误信封，直接复用统一映射。
     */
    private ResponseEntity<Object> openAiError(Throwable ex) {
        return ResponseEntity.status(AiGatewayErrorMapper.statusOf(ex))
                .contentType(MediaType.APPLICATION_JSON)
                .body((Object) AiGatewayErrorMapper.toResponse(ex));
    }

    private String anthropicErrorType(Throwable ex) {
        if (ex instanceof AiGatewayClientException clientException) {
            String type = clientException.getErrorCode().getType();
            if (StringUtils.hasText(type)) {
                return type;
            }
        }
        return "api_error";
    }

    private String messageOf(Throwable ex) {
        return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
    }
}
