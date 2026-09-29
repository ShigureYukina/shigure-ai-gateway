package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigDomain;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigPublisher;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;

class AiRuntimeConfigControllerTest {

    private RuntimeConfigService runtimeConfigService;
    private RuntimeConfigPublisher runtimeConfigPublisher;
    private AiRuntimeConfigController controller;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        AiGatewayProperties properties = new AiGatewayProperties();
        runtimeConfigService = Mockito.mock(RuntimeConfigService.class);
        runtimeConfigPublisher = Mockito.mock(RuntimeConfigPublisher.class);
        Mockito.when(runtimeConfigService.describe()).thenReturn(Mono.just(List.of(Map.of(
                "domain", "cache", "source", "yml", "version", 0L, "effective", Map.of()))));
        Mockito.when(runtimeConfigService.loadAll()).thenReturn(Mono.empty());
        Mockito.when(runtimeConfigPublisher.reset(any())).thenReturn(Mono.empty());

        controller = new AiRuntimeConfigController(properties, runtimeConfigService, runtimeConfigPublisher);
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    @Test
    void shouldDescribeDomainsAndPersistenceCapability() {
        Mockito.when(runtimeConfigService.persistable()).thenReturn(false);

        webTestClient.get()
                .uri("/v1/runtime-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.persisted").isEqualTo(false)
                .jsonPath("$.pollIntervalSeconds").isEqualTo(5)
                .jsonPath("$.items[0].domain").isEqualTo("cache")
                .jsonPath("$.items[0].source").isEqualTo("yml");
    }

    @Test
    void shouldReloadAllDomains() {
        webTestClient.post()
                .uri("/v1/runtime-config/reload")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(1);

        Mockito.verify(runtimeConfigService).loadAll();
    }

    @Test
    void shouldResetKnownDomain() {
        webTestClient.delete()
                .uri("/v1/runtime-config/cache")
                .exchange()
                .expectStatus().isOk();

        Mockito.verify(runtimeConfigPublisher).reset(RuntimeConfigDomain.CACHE);
    }

    @Test
    void shouldRejectUnknownDomain() {
        AiGatewayClientException ex = Assertions.assertThrows(AiGatewayClientException.class,
                () -> controller.reset("nope"));

        Assertions.assertEquals(AiGatewayErrorCode.BAD_REQUEST, ex.getErrorCode());
        Assertions.assertTrue(ex.getMessage().contains("rateLimit"), "错误信息里要列出可用域，省一次查文档");
        Mockito.verify(runtimeConfigPublisher, Mockito.never()).reset(any());
    }
}
