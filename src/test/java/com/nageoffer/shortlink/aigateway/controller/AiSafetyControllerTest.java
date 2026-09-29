package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigDomain;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class AiSafetyControllerTest {

    private AiGatewayProperties properties;
    private RuntimeConfigPublisher runtimeConfigPublisher;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        properties = new AiGatewayProperties();
        runtimeConfigPublisher = Mockito.mock(RuntimeConfigPublisher.class);
        Mockito.when(runtimeConfigPublisher.save(any(), any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(1)));
        webTestClient = WebTestClient.bindToController(new AiSafetyController(properties, runtimeConfigPublisher)).build();
    }

    @Test
    void shouldUpdateSafetyConfigAndDelegateToPublisher() {
        webTestClient.post()
                .uri("/v1/safety/config")
                .bodyValue(Map.of(
                        "enabled", true,
                        "outputStrategy", "block",
                        "blockedWords", List.of("badword"),
                        "piiPatterns", List.of("\\d{11}")
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.outputStrategy").isEqualTo("block")
                .jsonPath("$.blockedWords.length()").isEqualTo(1)
                .jsonPath("$.piiPatterns[0]").isEqualTo("\\d{11}");

        Mockito.verify(runtimeConfigPublisher).save(eq(RuntimeConfigDomain.SAFETY), any());
    }
}
