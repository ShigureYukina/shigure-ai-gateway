package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.routing.ProviderRoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class AiRoutingControllerTest {

    private ProviderRoutingService providerRoutingService;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        webTestClient = WebTestClient.bindToController(new AiRoutingController(providerRoutingService)).build();
    }

    @Test
    void shouldReturnRoutingConfigAndUpdateResult() {
        Mockito.when(providerRoutingService.routingConfig()).thenReturn(Map.of("defaultProvider", "openai"));
        Mockito.when(providerRoutingService.updateRoutingConfig(any())).thenReturn(Map.of("fallbackEnabled", true));

        webTestClient.get()
                .uri("/v1/routing/config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.defaultProvider").isEqualTo("openai");

        webTestClient.post()
                .uri("/v1/routing/config")
                .bodyValue(Map.of("fallbackEnabled", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.fallbackEnabled").isEqualTo(true);
    }

    @Test
    void shouldPreviewAndSimulateRouting() {
        Mockito.when(providerRoutingService.preview(eq("gpt-4o-mini"), any()))
                .thenReturn(Map.of("provider", "openai", "abHit", false));
        Mockito.when(providerRoutingService.simulateAb("gpt-4o-mini", 10))
                .thenReturn(Map.of("samples", 10, "providerDistribution", Map.of("openai", 10), "samplePreview", List.of()));

        webTestClient.get()
                .uri("/v1/routing/preview?model=gpt-4o-mini")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("openai")
                .jsonPath("$.abHit").isEqualTo(false);

        webTestClient.get()
                .uri("/v1/routing/simulate?model=gpt-4o-mini&samples=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.samples").isEqualTo(10)
                .jsonPath("$.providerDistribution.openai").isEqualTo(10);
    }
}
