package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.routing.ProviderRoutingService;
import com.nageoffer.shortlink.aigateway.routing.RoutingPlanResolver;
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

class AiRoutingControllerTest {

    private ProviderRoutingService providerRoutingService;
    private RoutingPlanResolver routingPlanResolver;
    private RuntimeConfigPublisher runtimeConfigPublisher;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        routingPlanResolver = Mockito.mock(RoutingPlanResolver.class);
        runtimeConfigPublisher = Mockito.mock(RuntimeConfigPublisher.class);
        Mockito.when(runtimeConfigPublisher.save(any(), any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(1)));
        webTestClient = WebTestClient.bindToController(
                new AiRoutingController(providerRoutingService, routingPlanResolver, runtimeConfigPublisher)).build();
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

        Mockito.verify(runtimeConfigPublisher).save(eq(RuntimeConfigDomain.ROUTING), any());
    }

    @Test
    void shouldPreviewAndSimulateRouting() {
        Mockito.when(routingPlanResolver.preview(eq("gpt-4o-mini"), any(), any()))
                .thenReturn(Map.of("provider", "openai", "abHit", false, "tenantScoped", false));
        Mockito.when(providerRoutingService.simulateAb("gpt-4o-mini", 10))
                .thenReturn(Map.of("samples", 10, "providerDistribution", Map.of("openai", 10), "samplePreview", List.of()));

        webTestClient.get()
                .uri("/v1/routing/preview?model=gpt-4o-mini")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("openai")
                .jsonPath("$.abHit").isEqualTo(false)
                .jsonPath("$.tenantScoped").isEqualTo(false);

        webTestClient.get()
                .uri("/v1/routing/simulate?model=gpt-4o-mini&samples=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.samples").isEqualTo(10)
                .jsonPath("$.providerDistribution.openai").isEqualTo(10);
    }

    @Test
    void shouldPassTenantIdThroughToPreview() {
        Mockito.when(routingPlanResolver.preview(eq("gpt-4o"), any(), eq("tenant-a")))
                .thenReturn(Map.of("provider", "openai", "model", "gpt-4o-mini", "tenantScoped", true));

        webTestClient.get()
                .uri("/v1/routing/preview?model=gpt-4o&tenantId=tenant-a")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.model").isEqualTo("gpt-4o-mini")
                .jsonPath("$.tenantScoped").isEqualTo(true);
    }
}
