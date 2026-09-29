package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTenantProperties;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import com.nageoffer.shortlink.aigateway.routing.ProviderGroupService;
import com.nageoffer.shortlink.aigateway.routing.ProviderHealthScoreService;
import com.nageoffer.shortlink.aigateway.routing.ProviderRoutingService;
import com.nageoffer.shortlink.aigateway.security.ApiKeyAuthService;
import com.nageoffer.shortlink.aigateway.sync.ModelCatalogSyncService;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.LinkedHashSet;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;

class AiModelsControllerTest {

    private AiGatewayProperties properties;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        properties = new AiGatewayProperties();
        properties.getUpstream().getModelAlias().put("gpt-4o-mini-compatible", "openai:gpt-4o-mini");

        ApiKeyAuthService apiKeyAuthService = Mockito.mock(ApiKeyAuthService.class);
        Mockito.when(apiKeyAuthService.authenticate(any())).thenReturn(new TenantContext("tenant-a", "app-a", "key-a"));

        webTestClient = WebTestClient.bindToController(new AiModelsController(
                apiKeyAuthService,
                properties,
                TenantConfigQueryService.fallbackOnly(properties),
                Mockito.mock(ModelCatalogSyncService.class),
                Mockito.mock(ProviderRoutingService.class))).build();
    }

    @Test
    void shouldReturnOpenAiCompatibleModelList() {
        webTestClient.get()
                .uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data[0].object").isEqualTo("model")
                .jsonPath("$.data[0].owned_by").isEqualTo("ai-gateway")
                .jsonPath("$.data[?(@.id == 'gpt-4o-mini-compatible')]").exists()
                .jsonPath("$.data[?(@.id == 'gpt-4o-mini')]").exists();
    }

    @Test
    void shouldOnlyExposeModelsAllowedForTenant() {
        properties.getTenant().setEnabled(true);
        AiGatewayTenantProperties.TenantModelPolicy policy = new AiGatewayTenantProperties.TenantModelPolicy();
        policy.getAllowedModels().add("gpt-4o-mini-compatible");
        policy.setDefaultModelAlias("default");
        policy.setDefaultModel("gpt-4o-mini-compatible");
        properties.getTenant().getModelPolicies().put("tenant-a", policy);

        // 清单必须是"这个凭证真能调用的模型"，否则客户端照着清单请求只会拿到 403
        webTestClient.get()
                .uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[?(@.id == 'gpt-4o-mini-compatible')]").exists()
                .jsonPath("$.data[?(@.id == 'default')]").exists()
                .jsonPath("$.data[?(@.id == 'gpt-4o-mini')]").doesNotExist();
    }

    @Test
    void shouldOnlyExposeDiscoveredModelsThatRouteToTheirProvider() {
        AiGatewayProperties routingProperties = new AiGatewayProperties();
        routingProperties.getUpstream().setDefaultProvider("openai");
        routingProperties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        routingProperties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");

        ModelCatalogSyncService modelCatalogSyncService = Mockito.mock(ModelCatalogSyncService.class);
        Mockito.when(modelCatalogSyncService.allModels())
                .thenReturn(new LinkedHashSet<>(List.of("gpt-4o", "claude-3-5-sonnet-latest")));
        Mockito.when(modelCatalogSyncService.modelsOf("openai")).thenReturn(List.of("gpt-4o"));
        Mockito.when(modelCatalogSyncService.modelsOf("claude")).thenReturn(List.of("claude-3-5-sonnet-latest"));

        ProviderHealthScoreService healthScoreService = Mockito.mock(ProviderHealthScoreService.class);
        ProviderRoutingService routingService = new ProviderRoutingService(routingProperties, healthScoreService,
                new ProviderGroupService(routingProperties, healthScoreService));

        ApiKeyAuthService apiKeyAuthService = Mockito.mock(ApiKeyAuthService.class);
        Mockito.when(apiKeyAuthService.authenticate(any())).thenReturn(new TenantContext("tenant-a", "app-a", "key-a"));
        WebTestClient client = WebTestClient.bindToController(new AiModelsController(
                        apiKeyAuthService,
                        routingProperties,
                        TenantConfigQueryService.fallbackOnly(routingProperties),
                        modelCatalogSyncService,
                        routingService))
                .build();

        // 默认走 openai，因此只在 openai 上发现过的模型才该出现；
        // 列在 openai 上不存在、却在 claude 上发现的模型，等于给客户端一个必然失败的清单
        client.get()
                .uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[?(@.id == 'gpt-4o')]").exists()
                .jsonPath("$.data[?(@.id == 'claude-3-5-sonnet-latest')]").doesNotExist();
    }

    @Test
    void shouldRequirePlatformApiKey() {
        ApiKeyAuthService apiKeyAuthService = Mockito.mock(ApiKeyAuthService.class);
        Mockito.when(apiKeyAuthService.authenticate(any())).thenThrow(
                new com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException(
                        com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode.UNAUTHORIZED, "缺少平台 API Key"));

        WebTestClient unauthenticatedClient = WebTestClient.bindToController(new AiModelsController(
                        apiKeyAuthService,
                        properties,
                        TenantConfigQueryService.fallbackOnly(properties),
                        Mockito.mock(ModelCatalogSyncService.class),
                        Mockito.mock(ProviderRoutingService.class)))
                .controllerAdvice(new AiGatewayExceptionHandler(properties))
                .build();

        unauthenticatedClient.get()
                .uri("/v1/models")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.code").isEqualTo("invalid_api_key");
    }
}
