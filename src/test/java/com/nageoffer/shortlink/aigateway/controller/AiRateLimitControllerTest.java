package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.governance.RedisTokenQuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class AiRateLimitControllerTest {

    private AiGatewayProperties properties;
    private RedisTokenQuotaService redisTokenQuotaService;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        properties = new AiGatewayProperties();
        redisTokenQuotaService = Mockito.mock(RedisTokenQuotaService.class);
        webTestClient = WebTestClient.bindToController(new AiRateLimitController(properties, redisTokenQuotaService)).build();
    }

    @Test
    void shouldReturnAndUpdateRateLimitConfig() {
        webTestClient.get()
                .uri("/v1/rate-limit/config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(false)
                .jsonPath("$.minTokenReserve").isEqualTo(128);

        webTestClient.post()
                .uri("/v1/rate-limit/config")
                .bodyValue(Map.of(
                        "enabled", true,
                        "defaultTokenQuotaPerMinute", 321,
                        "defaultTokenQuotaPerDay", 654,
                        "minTokenReserve", 99,
                        "keyDimensions", List.of("userId", "ip", "ip")
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.defaultTokenQuotaPerMinute").isEqualTo(321)
                .jsonPath("$.defaultTokenQuotaPerDay").isEqualTo(654)
                .jsonPath("$.minTokenReserve").isEqualTo(99)
                .jsonPath("$.keyDimensions.length()").isEqualTo(2);
    }

    @Test
    void shouldReturnUsageWithHeaderPreview() {
        Mockito.when(redisTokenQuotaService.currentUsage(any(), eq("openai"), eq("gpt-4o-mini")))
                .thenReturn(Map.of("minuteUsed", 10L, "dayUsed", 20L));

        webTestClient.get()
                .uri("/v1/rate-limit/usage?provider=openai&model=gpt-4o-mini")
                .header("userId", "u-1")
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Consumer", "demo")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.minuteUsed").isEqualTo(10)
                .jsonPath("$.dayUsed").isEqualTo(20)
                .jsonPath("$.headerPreview.userId").isEqualTo("u-1")
                .jsonPath("$.headerPreview.xForwardedFor").isEqualTo("127.0.0.1")
                .jsonPath("$.headerPreview.xConsumer").isEqualTo("demo");
    }
}
