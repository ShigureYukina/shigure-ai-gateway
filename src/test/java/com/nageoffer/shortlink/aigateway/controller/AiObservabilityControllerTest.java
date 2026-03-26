package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.observability.ModelMetricsSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.eq;

class AiObservabilityControllerTest {

    private AiGatewayMetricsRecorder metricsRecorder;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        metricsRecorder = Mockito.mock(AiGatewayMetricsRecorder.class);
        webTestClient = WebTestClient.bindToController(new AiObservabilityController(metricsRecorder)).build();
    }

    @Test
    void shouldReturnCurrentHourModelMetrics() {
        Mockito.when(metricsRecorder.currentHourSnapshot(eq("gpt-4o-mini")))
                .thenReturn(ModelMetricsSnapshot.builder()
                        .model("gpt-4o-mini")
                        .callCount(12L)
                        .successRate(0.75D)
                        .p95LatencyMillis(321L)
                        .totalCost(1.23D)
                        .build());

        webTestClient.get()
                .uri("/v1/metrics/models/gpt-4o-mini")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.model").isEqualTo("gpt-4o-mini")
                .jsonPath("$.callCount").isEqualTo(12)
                .jsonPath("$.successRate").isEqualTo(0.75)
                .jsonPath("$.p95LatencyMillis").isEqualTo(321)
                .jsonPath("$.totalCost").isEqualTo(1.23);
    }
}
