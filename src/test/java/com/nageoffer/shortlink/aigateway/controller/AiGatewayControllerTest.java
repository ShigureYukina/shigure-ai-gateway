package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.security.ApiKeyAuthService;
import com.nageoffer.shortlink.aigateway.service.AiGatewayService;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;

class AiGatewayControllerTest {

    private AiGatewayService aiGatewayService;
    private ApiKeyAuthService apiKeyAuthService;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        aiGatewayService = Mockito.mock(AiGatewayService.class);
        apiKeyAuthService = Mockito.mock(ApiKeyAuthService.class);
        Mockito.when(apiKeyAuthService.authenticate(any())).thenReturn(new TenantContext("tenant-a", "app-a", "key-a"));
        webTestClient = WebTestClient.bindToController(new AiGatewayController(aiGatewayService, apiKeyAuthService))
                .controllerAdvice(new AiGatewayExceptionHandler())
                .build();
    }

    @Test
    void shouldReturnJsonWhenStreamFalse() {
        Mockito.when(aiGatewayService.chatCompletion(any(), any(), any()))
                .thenReturn(Mono.just("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\"}"));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(nonStreamRequest())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.id").isEqualTo("chatcmpl-1")
                .jsonPath("$.object").isEqualTo("chat.completion");
    }

    @Test
    void shouldReturnSseWhenStreamTrue() {
        Mockito.when(aiGatewayService.streamChatCompletion(any(), any(), any()))
                .thenReturn(Flux.just("data: {\"id\":\"x1\"}\n\n", "data: [DONE]\n\n"));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(streamRequest())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> {
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("data: {\"id\":\"x1\"}"));
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("data: [DONE]"));
                });
    }

    @Test
    void shouldFailValidationWhenModelOrMessagesMissing() {
        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("stream", false))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void shouldReturnUnauthorizedWhenApiKeyAuthenticationFails() {
        Mockito.when(apiKeyAuthService.authenticate(any()))
                .thenThrow(new com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException(
                        com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode.UNAUTHORIZED,
                        "缺少平台 API Key"));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(nonStreamRequest())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.message").isEqualTo("缺少平台 API Key");
    }

    private AiChatCompletionReqDTO nonStreamRequest() {
        AiChatCompletionReqDTO req = baseRequest();
        req.setStream(false);
        return req;
    }

    private AiChatCompletionReqDTO streamRequest() {
        AiChatCompletionReqDTO req = baseRequest();
        req.setStream(true);
        return req;
    }

    private AiChatCompletionReqDTO baseRequest() {
        AiChatCompletionReqDTO req = new AiChatCompletionReqDTO();
        req.setModel("gpt-4o-mini");
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("user");
        message.setContent("hello");
        req.setMessages(List.of(message));
        return req;
    }
}
