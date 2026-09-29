package com.nageoffer.shortlink.aigateway.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.governance.ProviderKeyPoolService;
import com.nageoffer.shortlink.aigateway.governance.UpstreamCredentialService;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

class ModelCatalogSyncServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldDiscoverModelsFromCredentialedProviders() {
        AiGatewayProperties properties = credentialedProperties();
        ModelCatalogSyncService service = service(properties, () -> request -> Mono.just(
                request.url().toString().contains("anthropic")
                        ? ok("{\"data\":[{\"id\":\"claude-3-5-sonnet-latest\"}]}")
                        : ok("{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"}]}")));

        Map<String, Object> status = service.syncNow().block();

        Assertions.assertNotNull(status);
        Assertions.assertEquals("ok", status.get("state"));
        Assertions.assertEquals(List.of("gpt-4o", "gpt-4o-mini"), service.modelsOf("openai"));
        Assertions.assertEquals(List.of("claude-3-5-sonnet-latest"), service.modelsOf("claude"));
        Assertions.assertEquals(3, service.allModels().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSkipProviderWithoutCredential() {
        AiGatewayProperties properties = credentialedProperties();
        properties.getUpstream().getProviderCredentials().remove("claude");
        ModelCatalogSyncService service = service(properties, () -> request -> {
            Assertions.assertFalse(request.url().toString().contains("anthropic"), "未配置凭证的渠道不该被探测");
            return Mono.just(ok("{\"data\":[{\"id\":\"gpt-4o\"}]}"));
        });

        service.syncNow().block();

        Assertions.assertEquals(List.of("gpt-4o"), service.modelsOf("openai"));
        Assertions.assertTrue(service.modelsOf("claude").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldKeepPreviousSnapshotWhenNothingDiscovered() {
        AiGatewayProperties properties = credentialedProperties();
        java.util.concurrent.atomic.AtomicBoolean healthy = new java.util.concurrent.atomic.AtomicBoolean(true);
        ModelCatalogSyncService service = service(properties, () -> request -> healthy.get()
                ? Mono.just(ok("{\"data\":[{\"id\":\"gpt-4o\"}]}"))
                : Mono.error(new IllegalStateException("network down")));

        service.syncNow().block();
        Assertions.assertEquals(List.of("gpt-4o"), service.modelsOf("openai"));

        healthy.set(false);
        Map<String, Object> status = service.syncNow().block();

        // 一次失败不该把已经发现的清单清空，且状态要如实反映"这次没拿到东西"
        Assertions.assertNotNull(status);
        Assertions.assertEquals("degraded", status.get("state"));
        Assertions.assertNotNull(status.get("lastError"));
        Assertions.assertEquals(List.of("gpt-4o"), service.modelsOf("openai"));
    }

    private ModelCatalogSyncService service(AiGatewayProperties properties,
                                            Supplier<Function<ClientRequest, Mono<ClientResponse>>> responder) {
        TenantConfigQueryService queryService = TenantConfigQueryService.fallbackOnly(properties);
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> responder.get().apply(request))
                .build();
        UpstreamCredentialService credentialService = new UpstreamCredentialService(queryService, new ProviderKeyPoolService());
        UpstreamModelProbe modelProbe = new UpstreamModelProbe(properties, webClient,
                new UpstreamMetadataParser(new ObjectMapper()), credentialService);
        return new ModelCatalogSyncService(properties, credentialService, modelProbe, Mockito.mock(ObjectProvider.class));
    }

    private AiGatewayProperties credentialedProperties() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com/");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com/");
        AiGatewayProperties.ProviderCredential openai = new AiGatewayProperties.ProviderCredential();
        openai.setApiKey("sk-openai");
        openai.setAuthHeader(HttpHeaders.AUTHORIZATION);
        openai.setAuthScheme("Bearer");
        properties.getUpstream().getProviderCredentials().put("openai", openai);
        AiGatewayProperties.ProviderCredential claude = new AiGatewayProperties.ProviderCredential();
        claude.setApiKey("sk-claude");
        claude.setAuthHeader("x-api-key");
        claude.setAuthScheme("");
        properties.getUpstream().getProviderCredentials().put("claude", claude);
        return properties;
    }

    private ClientResponse ok(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
