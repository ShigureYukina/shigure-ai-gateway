package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigDomain;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class AiPluginControllerTest {

    private AiGatewayProperties properties;
    private RuntimeConfigPublisher runtimeConfigPublisher;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        properties = new AiGatewayProperties();
        runtimeConfigPublisher = Mockito.mock(RuntimeConfigPublisher.class);
        Mockito.when(runtimeConfigPublisher.save(any(), any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(1)));
        webTestClient = WebTestClient.bindToController(new AiPluginController(properties, runtimeConfigPublisher)).build();
    }

    @Test
    void shouldTogglePluginAndDelegateToPublisher() {
        webTestClient.post()
                .uri("/v1/plugins/toggle?name=pii&enabled=false")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("pii")
                .jsonPath("$.enabled").isEqualTo(false)
                .jsonPath("$.pluginEnabledMap.pii").isEqualTo(false);

        Mockito.verify(runtimeConfigPublisher).save(eq(RuntimeConfigDomain.PLUGIN), any());
    }

    @Test
    void shouldExposePluginConfiguration() {
        webTestClient.get()
                .uri("/v1/plugins/config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.pluginEnabledMap").exists();
    }
}
