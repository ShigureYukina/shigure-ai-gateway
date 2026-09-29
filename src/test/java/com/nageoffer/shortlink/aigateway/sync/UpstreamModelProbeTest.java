package com.nageoffer.shortlink.aigateway.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.governance.ProviderKeyPoolService;
import com.nageoffer.shortlink.aigateway.governance.UpstreamCredentialService;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import com.nageoffer.shortlink.aigateway.probe.ProbeErrorType;
import com.nageoffer.shortlink.aigateway.probe.ProbeOutcome;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class UpstreamModelProbeTest {

    @Test
    void shouldFetchModelsWithExplicitKeyAndConfiguredPath() {
        AiGatewayProperties properties = new AiGatewayProperties();
        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        List<String> models = probe(properties, captured, () -> Mono.just(ok("{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"}]}")))
                .probeWithExplicitKey("https://api.openai.com/v1", "sk-typed")
                .block();

        Assertions.assertEquals(List.of("gpt-4o", "gpt-4o-mini"), models);
        // baseUrl 自带 /v1 时不能再拼出 /v1/v1/models
        Assertions.assertEquals("https://api.openai.com/v1/models", captured.get().url().toString());
        Assertions.assertEquals("Bearer sk-typed",
                captured.get().headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void shouldUseStoredCredentialAndItsConfiguredAuthHeader() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com/");
        AiGatewayProperties.ProviderCredential credential = new AiGatewayProperties.ProviderCredential();
        credential.setApiKey("sk-claude");
        credential.setAuthHeader("x-api-key");
        credential.setAuthScheme("");
        properties.getUpstream().getProviderCredentials().put("claude", credential);
        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        List<String> models = probe(properties, captured, () -> Mono.just(ok("{\"data\":[{\"id\":\"claude-3-5-sonnet-latest\"}]}")))
                .probeWithStoredCredential("claude")
                .block();

        Assertions.assertEquals(List.of("claude-3-5-sonnet-latest"), models);
        Assertions.assertEquals("https://api.anthropic.com/v1/models", captured.get().url().toString());
        Assertions.assertEquals("sk-claude", captured.get().headers().getFirst("x-api-key"));
        Assertions.assertNull(captured.get().headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void shouldSkipProviderWithNoBaseUrlOrNoCredential() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        AtomicBoolean requested = new AtomicBoolean(false);
        UpstreamModelProbe probe = probe(properties, new AtomicReference<>(),
                () -> {
                    requested.set(true);
                    return Mono.just(ok("{\"data\":[{\"id\":\"gpt-4o\"}]}"));
                });

        // 没配 Key 的渠道问了必然 401，属于预期情况，不该被探测也不该报错
        Assertions.assertTrue(probe.probeWithStoredCredential("openai").block().isEmpty());
        // baseUrl 都没配的渠道连地址都拼不出来
        Assertions.assertTrue(probe.probeWithStoredCredential("ghost").block().isEmpty());
        Assertions.assertFalse(requested.get(), "不该发出任何上游请求");
    }

    @Test
    void shouldDegradeToEmptyListWhenStoredCredentialPathFails() {
        AiGatewayProperties properties = credentialedProperties();
        UpstreamModelProbe probe = probe(properties, new AtomicReference<>(), () -> Mono.just(
                ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).body("boom").build()));

        // 单个渠道抖动不该打断整批同步，也不该把上一份清单清空
        Assertions.assertTrue(probe.probeWithStoredCredential("openai").block().isEmpty());
    }

    @Test
    void shouldPropagateUpstreamErrorOnExplicitKeyPath() {
        AiGatewayProperties properties = new AiGatewayProperties();
        UpstreamModelProbe probe = probe(properties, new AtomicReference<>(), () -> Mono.just(
                ClientResponse.create(HttpStatus.UNAUTHORIZED).body("{\"error\":\"bad key\"}").build()));

        // 控制台需要把上游状态码原样转给用户，所以这条路径不能吞异常
        WebClientResponseException exception = Assertions.assertThrows(WebClientResponseException.class,
                () -> probe.probeWithExplicitKey("https://api.openai.com", "sk-bad").block());
        Assertions.assertEquals(401, exception.getStatusCode().value());
    }

    /**
     * 诊断入口要能区分 401 / 429 / 5xx / 超时 / 连不上 —— 分类错会让探测做错决定
     * （比如把"被限流"当成"渠道坏了"去禁用，可用容量反而更小）。
     */
    @Test
    void shouldDiagnoseSuccessWithModelsAndLatency() {
        UpstreamModelProbe probe = probe(credentialedProperties(), new AtomicReference<>(),
                () -> Mono.just(ok("{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"}]}")));

        ProbeOutcome outcome = probe.probeWithDiagnosis("openai").block();

        Assertions.assertTrue(outcome.ok());
        Assertions.assertEquals(ProbeErrorType.OK, outcome.errorType());
        Assertions.assertEquals(200, outcome.httpStatus());
        Assertions.assertEquals(List.of("gpt-4o", "gpt-4o-mini"), outcome.models());
        Assertions.assertEquals("openai", outcome.provider());
        Assertions.assertNull(outcome.describeError(), "成功时没有原因串");
        Assertions.assertTrue(outcome.latencyMillis() >= 0);
    }

    @Test
    void shouldDiagnoseHttpStatusesIntoDistinctTypes() {
        assertDiagnosed(HttpStatus.UNAUTHORIZED, ProbeErrorType.UNAUTHORIZED, 401);
        assertDiagnosed(HttpStatus.FORBIDDEN, ProbeErrorType.FORBIDDEN, 403);
        assertDiagnosed(HttpStatus.TOO_MANY_REQUESTS, ProbeErrorType.RATE_LIMITED, 429);
        assertDiagnosed(HttpStatus.BAD_GATEWAY, ProbeErrorType.UPSTREAM_5XX, 502);
        assertDiagnosed(HttpStatus.SERVICE_UNAVAILABLE, ProbeErrorType.UPSTREAM_5XX, 503);
        // 其他 4xx 归 BAD_RESPONSE：通了但请求本身有问题
        assertDiagnosed(HttpStatus.BAD_REQUEST, ProbeErrorType.BAD_RESPONSE, 400);
    }

    @Test
    void shouldDiagnoseEmptyModelListAsBadResponseNotSuccess() {
        // 200 但没有模型不算成功：与 ModelCatalogSyncService"空结果保留旧快照"同口径
        ProbeOutcome outcome = probe(credentialedProperties(), new AtomicReference<>(),
                () -> Mono.just(ok("{\"data\":[]}"))).probeWithDiagnosis("openai").block();

        Assertions.assertFalse(outcome.ok());
        Assertions.assertEquals(ProbeErrorType.BAD_RESPONSE, outcome.errorType());
        Assertions.assertTrue(outcome.models().isEmpty());
    }

    @Test
    void shouldDiagnoseTimeoutAndReadTimeout() {
        // Reactor 的 timeout 算子抛 TimeoutException
        ProbeOutcome plain = probe(credentialedProperties(), new AtomicReference<>(),
                () -> Mono.error(new TimeoutException("no response")))
                .probeWithDiagnosis("openai").block();
        Assertions.assertEquals(ProbeErrorType.TIMEOUT, plain.errorType());

        // Netty 的 ReadTimeoutException 会被包在外层异常里，必须沿 cause 链找到
        ProbeOutcome wrapped = probe(credentialedProperties(), new AtomicReference<>(),
                () -> Mono.error(new RuntimeException(ReadTimeoutException.INSTANCE)))
                .probeWithDiagnosis("openai").block();
        Assertions.assertEquals(ProbeErrorType.TIMEOUT, wrapped.errorType());
    }

    @Test
    void shouldDiagnoseConnectFailuresIncludingWrappedCause() {
        ProbeOutcome refused = probe(credentialedProperties(), new AtomicReference<>(),
                () -> Mono.error(new ConnectException("connection refused")))
                .probeWithDiagnosis("openai").block();
        Assertions.assertEquals(ProbeErrorType.CONNECT_FAILED, refused.errorType());

        ProbeOutcome unresolved = probe(credentialedProperties(), new AtomicReference<>(),
                () -> Mono.error(new RuntimeException(new UnknownHostException("ghost.example"))))
                .probeWithDiagnosis("openai").block();
        Assertions.assertEquals(ProbeErrorType.CONNECT_FAILED, unresolved.errorType());
    }

    @Test
    void shouldDiagnoseMissingCredentialWithoutSendingAnyRequest() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        AtomicBoolean requested = new AtomicBoolean(false);
        UpstreamModelProbe probe = probe(properties, new AtomicReference<>(),
                () -> {
                    requested.set(true);
                    return Mono.just(ok("{\"data\":[{\"id\":\"gpt-4o\"}]}"));
                });

        ProbeOutcome outcome = probe.probeWithDiagnosis("openai").block();

        Assertions.assertEquals(ProbeErrorType.NO_CREDENTIAL, outcome.errorType());
        Assertions.assertNull(outcome.httpStatus(), "根本没发出请求，不该有状态码");
        Assertions.assertFalse(requested.get());
    }

    @Test
    void shouldDiagnoseMissingBaseUrlAsConnectFailed() {
        ProbeOutcome outcome = probe(new AiGatewayProperties(), new AtomicReference<>(),
                () -> Mono.just(ok("{}"))).probeWithDiagnosis("ghost").block();

        Assertions.assertEquals(ProbeErrorType.CONNECT_FAILED, outcome.errorType());
        Assertions.assertTrue(outcome.message().contains("baseUrl"));
    }

    @Test
    void shouldNeverThrowFromDiagnosis() {
        // 主动探测在定时任务里跑，抛出就会打断整批探测（其他渠道的结果也一起丢）
        ProbeOutcome outcome = probe(credentialedProperties(), new AtomicReference<>(),
                () -> Mono.error(new IllegalStateException("something odd")))
                .probeWithDiagnosis("openai").block();

        Assertions.assertEquals(ProbeErrorType.BAD_RESPONSE, outcome.errorType(),
                "未识别异常兜底归 BAD_RESPONSE（fail-closed），不能当成成功");
    }

    private void assertDiagnosed(HttpStatus status, ProbeErrorType expected, int expectedStatus) {
        ProbeOutcome outcome = probe(credentialedProperties(), new AtomicReference<>(),
                () -> Mono.just(ClientResponse.create(status).body("{\"error\":\"x\"}").build()))
                .probeWithDiagnosis("openai").block();

        Assertions.assertEquals(expected, outcome.errorType(), "HTTP " + expectedStatus);
        Assertions.assertEquals(expectedStatus, outcome.httpStatus());
        Assertions.assertTrue(outcome.models().isEmpty());
        Assertions.assertTrue(outcome.describeError().contains(expected.name()));
    }

    private UpstreamModelProbe probe(AiGatewayProperties properties,
                                    AtomicReference<ClientRequest> captured,
                                    java.util.function.Supplier<Mono<ClientResponse>> responder) {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return responder.get();
                })
                .build();
        UpstreamCredentialService credentialService = new UpstreamCredentialService(
                TenantConfigQueryService.fallbackOnly(properties), new ProviderKeyPoolService());
        return new UpstreamModelProbe(properties, webClient, new UpstreamMetadataParser(new ObjectMapper()), credentialService);
    }

    private AiGatewayProperties credentialedProperties() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        AiGatewayProperties.ProviderCredential credential = new AiGatewayProperties.ProviderCredential();
        credential.setApiKey("sk-openai");
        credential.setAuthHeader(HttpHeaders.AUTHORIZATION);
        credential.setAuthScheme("Bearer");
        properties.getUpstream().getProviderCredentials().put("openai", credential);
        return properties;
    }

    private ClientResponse ok(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
