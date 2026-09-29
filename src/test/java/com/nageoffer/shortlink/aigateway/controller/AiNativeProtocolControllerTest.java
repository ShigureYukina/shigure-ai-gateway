package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.protocol.anthropic.AnthropicMessagesMapper;
import com.nageoffer.shortlink.aigateway.protocol.anthropic.AnthropicStreamMapper;
import com.nageoffer.shortlink.aigateway.protocol.responses.OpenAiResponsesMapper;
import com.nageoffer.shortlink.aigateway.protocol.responses.ResponsesStreamMapper;
import com.nageoffer.shortlink.aigateway.security.ApiKeyAuthService;
import com.nageoffer.shortlink.aigateway.service.AiGatewayService;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 原生协议入口：Anthropic SDK 与 Responses 客户端都要能"照着官方文档"直接用。
 */
class AiNativeProtocolControllerTest {

    private static final String OPEN_AI_BODY = """
            { "id": "chatcmpl-1", "model": "gpt-4o-mini",
              "choices": [ { "index": 0, "finish_reason": "stop",
                "message": { "role": "assistant", "content": "你好" } } ],
              "usage": { "prompt_tokens": 5, "completion_tokens": 2 } }
            """;

    private AiGatewayService aiGatewayService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        aiGatewayService = Mockito.mock(AiGatewayService.class);
        ApiKeyAuthService apiKeyAuthService = Mockito.mock(ApiKeyAuthService.class);
        Mockito.when(apiKeyAuthService.authenticate(Mockito.any())).thenReturn(new TenantContext("tenant-a", "app-a", "key-a"));
        webTestClient = WebTestClient.bindToController(new AiNativeProtocolController(
                        aiGatewayService,
                        apiKeyAuthService,
                        new AnthropicMessagesMapper(),
                        new AnthropicStreamMapper(),
                        new OpenAiResponsesMapper(),
                        new ResponsesStreamMapper()))
                .build();
    }

    @Test
    void shouldServeAnthropicMessagesWithNativeResponseShape() {
        Mockito.when(aiGatewayService.chatCompletion(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(OPEN_AI_BODY));

        webTestClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        { "model": "claude-compatible", "max_tokens": 64,
                          "messages": [ { "role": "user", "content": "hi" } ] }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.type").isEqualTo("message")
                .jsonPath("$.role").isEqualTo("assistant")
                .jsonPath("$.model").isEqualTo("claude-compatible")
                .jsonPath("$.content[0].type").isEqualTo("text")
                .jsonPath("$.content[0].text").isEqualTo("你好")
                .jsonPath("$.stop_reason").isEqualTo("end_turn")
                .jsonPath("$.usage.input_tokens").isEqualTo(5);

        ArgumentCaptor<AiChatCompletionReqDTO> captor = ArgumentCaptor.forClass(AiChatCompletionReqDTO.class);
        Mockito.verify(aiGatewayService).chatCompletion(captor.capture(), Mockito.any(), Mockito.any());
        Assertions.assertEquals("claude-compatible", captor.getValue().getModel());
        Assertions.assertEquals(64, captor.getValue().getMaxTokens());
        Assertions.assertEquals("user", captor.getValue().getMessages().get(0).getRole());
    }

    @Test
    void shouldReturnAnthropicErrorEnvelopeForGatewayFailure() {
        Mockito.when(aiGatewayService.chatCompletion(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.error(new AiGatewayClientException(AiGatewayErrorCode.QUOTA_EXCEEDED, "Token 配额不足")));

        webTestClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        { "model": "m", "max_tokens": 8, "messages": [ { "role": "user", "content": "hi" } ] }
                        """)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.type").isEqualTo("error")
                .jsonPath("$.error.type").isEqualTo("rate_limit_error")
                .jsonPath("$.error.message").isEqualTo("Token 配额不足");
    }

    @Test
    void shouldRejectAnthropicRequestWithoutModel() {
        webTestClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        { "max_tokens": 8, "messages": [ { "role": "user", "content": "hi" } ] }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo("error")
                .jsonPath("$.error.message").isEqualTo("model 不能为空");
    }

    @Test
    void shouldCountAnthropicInputTokens() {
        webTestClient.post()
                .uri("/v1/messages/count_tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        { "model": "m", "messages": [ { "role": "user", "content": "12345678" } ] }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.input_tokens").isEqualTo(2);
    }

    @Test
    void shouldStreamAnthropicEventsOverSse() {
        String chunk = """
                { "id": "c1", "choices": [ { "index": 0, "delta": { "content": "你" }, "finish_reason": null } ] }
                """;
        String finish = """
                { "id": "c1", "choices": [ { "index": 0, "delta": {}, "finish_reason": "stop" } ] }
                """;
        Mockito.when(aiGatewayService.streamChatCompletion(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(chunk.trim(), finish.trim(), "[DONE]"));

        String body = new String(webTestClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        { "model": "m", "max_tokens": 8, "stream": true,
                          "messages": [ { "role": "user", "content": "hi" } ] }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody()
                .returnResult()
                .getResponseBody());

        Assertions.assertTrue(body.contains("event:message_start"), body);
        Assertions.assertTrue(body.contains("event:content_block_delta"), body);
        Assertions.assertTrue(body.contains("event:message_stop"), body);
        Assertions.assertFalse(body.contains("[DONE]"));
    }

    @Test
    void shouldServeResponsesApiWithOutputText() {
        Mockito.when(aiGatewayService.chatCompletion(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(OPEN_AI_BODY));

        webTestClient.post()
                .uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        { "model": "gpt-4o-mini", "input": "hi" }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("response")
                .jsonPath("$.status").isEqualTo("completed")
                .jsonPath("$.output_text").isEqualTo("你好")
                .jsonPath("$.output[0].content[0].type").isEqualTo("output_text")
                .jsonPath("$.usage.input_tokens").isEqualTo(5);
    }

    @Test
    void shouldReturnOpenAiErrorEnvelopeForResponsesApi() {
        Mockito.when(aiGatewayService.chatCompletion(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.error(new AiGatewayClientException(AiGatewayErrorCode.UNAUTHORIZED, "缺少平台 API Key")));

        webTestClient.post()
                .uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        { "model": "gpt-4o-mini", "input": "hi" }
                        """)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.code").isEqualTo("invalid_api_key");
    }
}
