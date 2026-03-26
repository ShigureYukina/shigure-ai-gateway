package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.dto.req.ConsoleLoginReqDTO;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayUpstreamException;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

class AiGatewayExceptionHandlerTest {

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(new ThrowingTestController())
                .controllerAdvice(new AiGatewayExceptionHandler())
                .build();
    }

    @Test
    void shouldHandleValidationException() {
        webTestClient.post()
                .uri("/test/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.message").exists();
    }

    @Test
    void shouldHandleClientException() {
        webTestClient.post()
                .uri("/test/client")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.message").isEqualTo("quota exceeded");
    }

    @Test
    void shouldHandleUpstreamException() {
        webTestClient.post()
                .uri("/test/upstream")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.message").isEqualTo("upstream failed");
    }

    @Test
    void shouldHandleGenericException() {
        webTestClient.post()
                .uri("/test/generic")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.status").isEqualTo(500)
                .jsonPath("$.message").isEqualTo("boom");
    }

    @RestController
    @RequestMapping("/test")
    static class ThrowingTestController {

        @PostMapping("/validate")
        Mono<String> validate(@Valid @RequestBody ConsoleLoginReqDTO request) {
            return Mono.just("ok");
        }

        @PostMapping("/client")
        Mono<String> client() {
            return Mono.error(new AiGatewayClientException(AiGatewayErrorCode.QUOTA_EXCEEDED, "quota exceeded"));
        }

        @PostMapping("/upstream")
        Mono<String> upstream() {
            return Mono.error(new AiGatewayUpstreamException(503, "upstream failed", true));
        }

        @PostMapping("/generic")
        Mono<String> generic() {
            return Mono.error(new IllegalStateException("boom"));
        }
    }
}
