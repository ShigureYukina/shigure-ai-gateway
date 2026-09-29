package com.nageoffer.shortlink.aigateway.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.repository.AiModelPriceSyncRepository;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Supplier;

class ModelPriceSyncServiceTest {

    private static final String PRICE_JSON = """
            {
              "openai": {
                "models": {
                  "gpt-4o-mini": { "id": "gpt-4o-mini", "cost": { "input": 150, "output": 600 } }
                }
              }
            }
            """;

    @Test
    @SuppressWarnings("unchecked")
    void shouldApplySyncedPricesAndReportOk() {
        AiGatewayProperties properties = new AiGatewayProperties();
        TenantConfigQueryService queryService = TenantConfigQueryService.fallbackOnly(properties);
        ModelPriceSyncService service = new ModelPriceSyncService(properties, webClient(() -> ok(PRICE_JSON)),
                new UpstreamMetadataParser(new ObjectMapper()), queryService,
                Mockito.mock(ObjectProvider.class));

        Map<String, Object> status = service.syncNow().block();

        Assertions.assertNotNull(status);
        Assertions.assertEquals("ok", status.get("state"));
        Assertions.assertEquals(1, status.get("modelCount"));
        // 同步结果直接落到读取层，无需重启或落库
        Assertions.assertEquals(0.15D,
                queryService.findSyncedModelPrice("gpt-4o-mini").orElseThrow().getInputPer1k(), 0.000001);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReportFailureWithoutThrowing() {
        AiGatewayProperties properties = new AiGatewayProperties();
        TenantConfigQueryService queryService = TenantConfigQueryService.fallbackOnly(properties);
        ModelPriceSyncService service = new ModelPriceSyncService(properties,
                webClient(() -> ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).body("upstream down").build()),
                new UpstreamMetadataParser(new ObjectMapper()), queryService,
                Mockito.mock(ObjectProvider.class));

        Map<String, Object> status = service.syncNow().block();

        Assertions.assertNotNull(status);
        Assertions.assertEquals("failed", status.get("state"));
        Assertions.assertNotNull(status.get("lastError"));
        Assertions.assertEquals(0, queryService.syncedPriceCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSkipWhenDisabled() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getSync().getPrice().setEnabled(false);
        ModelPriceSyncService service = new ModelPriceSyncService(properties, webClient(() -> ok(PRICE_JSON)),
                new UpstreamMetadataParser(new ObjectMapper()), TenantConfigQueryService.fallbackOnly(properties),
                Mockito.mock(ObjectProvider.class));

        Assertions.assertEquals("disabled", service.syncNow().block().get("state"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPersistSyncedPricesWhenRepositoryAvailable() {
        AiGatewayProperties properties = new AiGatewayProperties();
        AiModelPriceSyncRepository repository = Mockito.mock(AiModelPriceSyncRepository.class);
        Mockito.when(repository.deleteBySource("models.dev")).thenReturn(Mono.empty());
        Mockito.when(repository.saveAll(Mockito.anyList())).thenReturn(reactor.core.publisher.Flux.empty());
        ObjectProvider<AiModelPriceSyncRepository> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(repository);
        ModelPriceSyncService service = new ModelPriceSyncService(properties, webClient(() -> ok(PRICE_JSON)),
                new UpstreamMetadataParser(new ObjectMapper()), TenantConfigQueryService.fallbackOnly(properties), provider);

        service.syncNow().block();

        Mockito.verify(repository).deleteBySource("models.dev");
        Mockito.verify(repository).saveAll(Mockito.anyList());
    }

    private WebClient webClient(Supplier<ClientResponse> responder) {
        return WebClient.builder().exchangeFunction(request -> Mono.just(responder.get())).build();
    }

    private ClientResponse ok(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
