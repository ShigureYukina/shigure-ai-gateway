package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.governance.AiCacheStatsService;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigDomain;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigPublisher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class AiCacheControllerTest {

    private AiGatewayProperties properties;
    private RuntimeConfigPublisher runtimeConfigPublisher;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        properties = new AiGatewayProperties();
        runtimeConfigPublisher = Mockito.mock(RuntimeConfigPublisher.class);
        Mockito.when(runtimeConfigPublisher.save(any(), any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(1)));
        webTestClient = WebTestClient.bindToController(
                new AiCacheController(properties, Mockito.mock(AiCacheStatsService.class), runtimeConfigPublisher)).build();
    }

    @Test
    void shouldUpdateCacheConfigAndDelegateToPublisher() {
        webTestClient.post()
                .uri("/v1/cache/config")
                .bodyValue(Map.of("enabled", true, "ttlSeconds", 120, "semanticCacheEnabled", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.ttlSeconds").isEqualTo(120)
                .jsonPath("$.semanticCacheEnabled").isEqualTo(true);

        Mockito.verify(runtimeConfigPublisher).save(eq(RuntimeConfigDomain.CACHE), any());
    }

    @Test
    void shouldIgnoreNonPositiveTtl() {
        Duration before = properties.getCache().getTtl();

        webTestClient.post()
                .uri("/v1/cache/config")
                .bodyValue(Map.of("ttlSeconds", 0))
                .exchange()
                .expectStatus().isOk();

        Assertions.assertEquals(before, properties.getCache().getTtl());
    }

    @Test
    void shouldIgnoreNonNumericTtl() {
        Duration before = properties.getCache().getTtl();

        webTestClient.post()
                .uri("/v1/cache/config")
                .bodyValue(Map.of("ttlSeconds", "not-a-number"))
                .exchange()
                .expectStatus().isOk();

        Assertions.assertEquals(before, properties.getCache().getTtl());
    }
}
