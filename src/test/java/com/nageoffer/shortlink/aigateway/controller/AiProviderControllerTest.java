package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.sync.UpstreamModelProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;

/**
 * 控制台"测试连接"入口。
 * <p>
 * 地址统一用<b>字面量 IP</b>（{@code 127.0.0.1}）：{@code InetAddress.getAllByName} 对字面量
 * 不会发起 DNS 查询，用例因此不依赖网络。这也是"dev 放行私网"的真实场景
 * —— 本地把上游指到 Ollama 就是 {@code http://127.0.0.1:11434}。
 */
class AiProviderControllerTest {

    private static final String LOCAL_BASE_URL = "http://127.0.0.1:11434/v1";

    private UpstreamModelProbe probe;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        probe = Mockito.mock(UpstreamModelProbe.class);
        webTestClient = WebTestClient.bindToController(new AiProviderController(probe, properties(true))).build();
    }

    @Test
    void shouldReturnModelsAndCount() {
        Mockito.when(probe.probeWithExplicitKey(LOCAL_BASE_URL, "sk-typed"))
                .thenReturn(Mono.just(List.of("gpt-4o", "gpt-4o-mini")));

        webTestClient.post().uri("/v1/providers/models")
                .bodyValue(Map.of("baseUrl", LOCAL_BASE_URL + "/", "apiKey", "sk-typed"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(2)
                .jsonPath("$.models[0]").isEqualTo("gpt-4o")
                .jsonPath("$.models[1]").isEqualTo("gpt-4o-mini");

        // baseUrl 先归一化（去掉尾部斜杠）再进探测，探测拿到的是一份规范地址
        Mockito.verify(probe).probeWithExplicitKey(LOCAL_BASE_URL, "sk-typed");
    }

    @Test
    void shouldRejectInvalidBaseUrlWithoutTouchingUpstream() {
        webTestClient.post().uri("/v1/providers/models")
                .bodyValue(Map.of("baseUrl", "ftp://host", "apiKey", "sk-typed"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.models").isEmpty();

        Mockito.verifyNoInteractions(probe);
    }

    @Test
    void shouldRejectPrivateBaseUrlWhenPrivateAddressesAreNotAllowed() {
        // prod 的默认姿态：allow-private-addresses=false。127.0.0.1 必须被拦在发请求之前，
        // 否则"测试连接"就成了一个能扫内网的可达端点。
        WebTestClient strictClient = WebTestClient.bindToController(
                new AiProviderController(probe, properties(false))).build();

        strictClient.post().uri("/v1/providers/models")
                .bodyValue(Map.of("baseUrl", LOCAL_BASE_URL, "apiKey", "sk-typed"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.models").isEmpty();

        Mockito.verifyNoInteractions(probe);
    }

    @Test
    void shouldRejectMetadataHostEvenWhenPrivateAddressesAreAllowed() {
        // 元数据主机名不受开关影响：放行私网是为了本地调试，不是为了能读实例凭证
        WebTestClient client = WebTestClient.bindToController(
                new AiProviderController(probe, properties(true))).build();

        client.post().uri("/v1/providers/models")
                .bodyValue(Map.of("baseUrl", "http://metadata.google.internal/computeMetadata", "apiKey", "sk-typed"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.models").isEmpty();

        Mockito.verifyNoInteractions(probe);
    }

    @Test
    void shouldPassUpstreamStatusThroughWithoutLeakingResponseBody() {
        Mockito.when(probe.probeWithExplicitKey(anyString(), anyString()))
                .thenReturn(Mono.error(WebClientResponseException.create(401, "Unauthorized",
                        HttpHeaders.EMPTY, "upstream-secret-body".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8)));

        webTestClient.post().uri("/v1/providers/models")
                .bodyValue(Map.of("baseUrl", LOCAL_BASE_URL, "apiKey", "sk-bad"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.models").isEmpty()
                // detail 不回传：它是上游系统的响应体，原样透出等于开了一条数据外带通道
                .jsonPath("$.detail").doesNotExist();
    }

    @Test
    void shouldMapTransportFailureToBadGateway() {
        Mockito.when(probe.probeWithExplicitKey(anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("connection reset")));

        webTestClient.post().uri("/v1/providers/models")
                .bodyValue(Map.of("baseUrl", LOCAL_BASE_URL, "apiKey", "sk-typed"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectBody()
                .jsonPath("$.models").isEmpty();
    }

    @Test
    void shouldReturnEmptyListWhenUpstreamHasNoModels() {
        Mockito.when(probe.probeWithExplicitKey(anyString(), anyString()))
                .thenReturn(Mono.just(List.of()));

        webTestClient.post().uri("/v1/providers/models")
                .bodyValue(Map.of("baseUrl", LOCAL_BASE_URL, "apiKey", "sk-typed"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(0)
                .jsonPath("$.models").isEmpty();
    }

    private static AiGatewayProperties properties(boolean allowPrivateAddresses) {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getSecurity().getSsrf().setAllowPrivateAddresses(allowPrivateAddresses);
        return properties;
    }
}
