package com.nageoffer.shortlink.aigateway.persistence.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.entity.AiModelPriceEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantAppEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantApiKeyEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelPolicyEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantQuotaPolicyEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.AiModelPriceRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantAppRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantApiKeyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelAllowedRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelMappingRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelPolicyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantQuotaPolicyRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class TenantConfigManagementServiceTest {

    private AiGatewayProperties properties;
    private TenantConfigQueryService tenantConfigQueryService;
    private TenantRepository tenantRepository;
    private TenantAppRepository tenantAppRepository;
    private TenantApiKeyRepository tenantApiKeyRepository;
    private TenantModelPolicyRepository tenantModelPolicyRepository;
    private TenantModelAllowedRepository tenantModelAllowedRepository;
    private TenantModelMappingRepository tenantModelMappingRepository;
    private TenantQuotaPolicyRepository tenantQuotaPolicyRepository;
    private AiModelPriceRepository aiModelPriceRepository;
    private TenantConfigManagementService service;

    @BeforeEach
    void setUp() {
        properties = new AiGatewayProperties();
        properties.getTenant().getPersistence().setEnabled(true);
        tenantConfigQueryService = Mockito.mock(TenantConfigQueryService.class);
        Mockito.when(tenantConfigQueryService.refreshFromDatabaseReactive()).thenReturn(Mono.empty());
        tenantRepository = Mockito.mock(TenantRepository.class);
        tenantAppRepository = Mockito.mock(TenantAppRepository.class);
        tenantApiKeyRepository = Mockito.mock(TenantApiKeyRepository.class);
        tenantModelPolicyRepository = Mockito.mock(TenantModelPolicyRepository.class);
        tenantModelAllowedRepository = Mockito.mock(TenantModelAllowedRepository.class);
        tenantModelMappingRepository = Mockito.mock(TenantModelMappingRepository.class);
        tenantQuotaPolicyRepository = Mockito.mock(TenantQuotaPolicyRepository.class);
        aiModelPriceRepository = Mockito.mock(AiModelPriceRepository.class);
        service = new TenantConfigManagementService(
                properties,
                tenantConfigQueryService,
                tenantRepository,
                tenantAppRepository,
                tenantApiKeyRepository,
                tenantModelPolicyRepository,
                tenantModelAllowedRepository,
                tenantModelMappingRepository,
                tenantQuotaPolicyRepository,
                aiModelPriceRepository
        );
    }

    @Test
    void shouldUpsertApiKeyAndRefreshCache() {
        TenantApiKeyEntity saved = new TenantApiKeyEntity();
        saved.setTenantId("tenant-a");
        saved.setAppId("app-a");
        saved.setKeyId("key-a");
        saved.setApiKey("secret-a");
        saved.setEnabled(true);

        Mockito.when(tenantApiKeyRepository.findByApiKey("secret-a")).thenReturn(Mono.empty());
        Mockito.when(tenantApiKeyRepository.save(any(TenantApiKeyEntity.class))).thenReturn(Mono.just(saved));

        Map<String, Object> result = service.upsertApiKey(Map.of(
                "tenantId", "tenant-a",
                "appId", "app-a",
                "keyId", "key-a",
                "apiKey", "secret-a",
                "enabled", true
        )).block();

        Assertions.assertEquals("tenant-a", result.get("tenantId"));
        Assertions.assertEquals("app-a", result.get("appId"));
        Mockito.verify(tenantConfigQueryService).refreshFromDatabaseReactive();
    }

    @Test
    void shouldUpsertTenantAndTenantApp() {
        TenantEntity tenantSaved = new TenantEntity();
        tenantSaved.setTenantId("tenant-a");
        tenantSaved.setTenantName("Tenant A");
        tenantSaved.setTenantStatus("ACTIVE");
        Mockito.when(tenantRepository.findByTenantId("tenant-a")).thenReturn(Mono.empty());
        Mockito.when(tenantRepository.save(any(TenantEntity.class))).thenReturn(Mono.just(tenantSaved));

        TenantAppEntity appSaved = new TenantAppEntity();
        appSaved.setTenantId("tenant-a");
        appSaved.setAppId("app-a");
        appSaved.setAppName("App A");
        appSaved.setAppStatus("ACTIVE");
        Mockito.when(tenantAppRepository.findByTenantIdAndAppId("tenant-a", "app-a")).thenReturn(Mono.empty());
        Mockito.when(tenantAppRepository.save(any(TenantAppEntity.class))).thenReturn(Mono.just(appSaved));

        Map<String, Object> tenantResult = service.upsertTenant(Map.of(
                "tenantId", "tenant-a",
                "tenantName", "Tenant A",
                "tenantStatus", "ACTIVE"
        )).block();
        Map<String, Object> appResult = service.upsertTenantApp("tenant-a", Map.of(
                "appId", "app-a",
                "appName", "App A",
                "appStatus", "ACTIVE"
        )).block();

        Assertions.assertEquals("tenant-a", tenantResult.get("tenantId"));
        Assertions.assertEquals("app-a", appResult.get("appId"));
        Mockito.verify(tenantConfigQueryService, Mockito.times(2)).refreshFromDatabaseReactive();
    }

    @Test
    void shouldQueryTenantAndTenantApps() {
        TenantEntity tenant = new TenantEntity();
        tenant.setTenantId("tenant-a");
        tenant.setTenantName("Tenant A");
        tenant.setTenantStatus("ACTIVE");
        tenant.setDescription("desc-a");
        Mockito.when(tenantRepository.findByTenantId("tenant-a")).thenReturn(Mono.just(tenant));

        TenantAppEntity app = new TenantAppEntity();
        app.setTenantId("tenant-a");
        app.setAppId("app-a");
        app.setAppName("App A");
        app.setAppStatus("ACTIVE");
        app.setDescription("app-desc");
        Mockito.when(tenantAppRepository.findByTenantIdAndAppId("tenant-a", "app-a")).thenReturn(Mono.just(app));
        Mockito.when(tenantAppRepository.findAllByTenantId("tenant-a")).thenReturn(Flux.just(app));

        Map<String, Object> tenantResult = service.getTenant("tenant-a").block();
        Map<String, Object> appResult = service.getTenantApp("tenant-a", "app-a").block();
        java.util.List<Map<String, Object>> appList = service.listTenantApps("tenant-a").collectList().block();

        Assertions.assertEquals("Tenant A", tenantResult.get("tenantName"));
        Assertions.assertEquals("app-a", appResult.get("appId"));
        Assertions.assertEquals(1, appList.size());
        Assertions.assertEquals("App A", appList.get(0).get("appName"));
    }

    @Test
    void shouldUpsertModelPolicyAndReplaceChildren() {
        TenantModelPolicyEntity saved = new TenantModelPolicyEntity();
        saved.setTenantId("tenant-a");
        saved.setEnabled(true);
        saved.setDefaultModelAlias("default");
        saved.setDefaultModel("gpt-4o-mini-compatible");

        Mockito.when(tenantModelPolicyRepository.findByTenantId("tenant-a")).thenReturn(Mono.empty());
        Mockito.when(tenantModelPolicyRepository.save(any(TenantModelPolicyEntity.class))).thenReturn(Mono.just(saved));
        Mockito.when(tenantModelAllowedRepository.deleteAllByTenantId("tenant-a")).thenReturn(Mono.empty());
        Mockito.when(tenantModelMappingRepository.deleteAllByTenantId("tenant-a")).thenReturn(Mono.empty());
        Mockito.when(tenantModelAllowedRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(tenantModelMappingRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        Map<String, Object> result = service.upsertModelPolicy("tenant-a", Map.of(
                "enabled", true,
                "allowedModels", List.of("gpt-4o-mini-compatible", "gpt-4o-mini"),
                "modelMappings", Map.of("default", "gpt-4o-mini-compatible"),
                "defaultModelAlias", "default",
                "defaultModel", "gpt-4o-mini-compatible"
        )).block();

        Assertions.assertEquals("tenant-a", result.get("tenantId"));
        Mockito.verify(tenantModelAllowedRepository).deleteAllByTenantId("tenant-a");
        Mockito.verify(tenantModelMappingRepository).deleteAllByTenantId("tenant-a");
        Mockito.verify(tenantConfigQueryService).refreshFromDatabaseReactive();
    }

    @Test
    void shouldDeleteTenantScopedConfigurationsAndRefresh() {
        Mockito.when(tenantApiKeyRepository.deleteAllByTenantId("tenant-a")).thenReturn(Mono.empty());
        Mockito.when(tenantModelAllowedRepository.deleteAllByTenantId("tenant-a")).thenReturn(Mono.empty());
        Mockito.when(tenantModelMappingRepository.deleteAllByTenantId("tenant-a")).thenReturn(Mono.empty());
        Mockito.when(tenantQuotaPolicyRepository.deleteByTenantId("tenant-a")).thenReturn(Mono.empty());
        Mockito.when(tenantModelPolicyRepository.deleteByTenantId("tenant-a")).thenReturn(Mono.empty());
        Mockito.when(tenantAppRepository.deleteAllByTenantId("tenant-a")).thenReturn(Mono.empty());
        Mockito.when(tenantRepository.deleteByTenantId("tenant-a")).thenReturn(Mono.empty());
        Mockito.when(tenantAppRepository.deleteByTenantIdAndAppId("tenant-a", "app-a")).thenReturn(Mono.empty());
        Mockito.when(tenantApiKeyRepository.deleteByApiKey("secret-a")).thenReturn(Mono.empty());
        Mockito.when(aiModelPriceRepository.deleteByModel("gpt-4o-mini")).thenReturn(Mono.empty());

        Map<String, Object> tenantDelete = service.deleteTenant("tenant-a").block();
        Map<String, Object> appDelete = service.deleteTenantApp("tenant-a", "app-a").block();
        Map<String, Object> keyDelete = service.deleteApiKey("secret-a").block();
        Map<String, Object> policyDelete = service.deleteModelPolicy("tenant-a").block();
        Map<String, Object> quotaDelete = service.deleteQuotaPolicy("tenant-a").block();
        Map<String, Object> priceDelete = service.deleteModelPrice("gpt-4o-mini").block();

        Assertions.assertEquals(true, tenantDelete.get("deleted"));
        Assertions.assertEquals("app-a", appDelete.get("appId"));
        Assertions.assertEquals("secret-a", keyDelete.get("apiKey"));
        Assertions.assertEquals("tenant-a", policyDelete.get("tenantId"));
        Assertions.assertEquals("tenant-a", quotaDelete.get("tenantId"));
        Assertions.assertEquals("gpt-4o-mini", priceDelete.get("model"));
        Mockito.verify(tenantConfigQueryService, Mockito.times(6)).refreshFromDatabaseReactive();
    }

    @Test
    void shouldUpsertQuotaAndModelPrice() {
        TenantQuotaPolicyEntity quotaSaved = new TenantQuotaPolicyEntity();
        quotaSaved.setTenantId("tenant-a");
        quotaSaved.setEnabled(true);
        quotaSaved.setTokenQuotaPerMinute(100L);
        quotaSaved.setTokenQuotaPerDay(1000L);
        quotaSaved.setTokenQuotaPerMonth(30000L);
        Mockito.when(tenantQuotaPolicyRepository.findByTenantId("tenant-a")).thenReturn(Mono.empty());
        Mockito.when(tenantQuotaPolicyRepository.save(any())).thenReturn(Mono.just(quotaSaved));

        AiModelPriceEntity priceSaved = new AiModelPriceEntity();
        priceSaved.setModel("gpt-4o-mini");
        priceSaved.setInputPer1k(0.15D);
        priceSaved.setOutputPer1k(0.6D);
        priceSaved.setEnabled(true);
        Mockito.when(aiModelPriceRepository.findByModel("gpt-4o-mini")).thenReturn(Mono.empty());
        Mockito.when(aiModelPriceRepository.save(any())).thenReturn(Mono.just(priceSaved));

        Map<String, Object> quotaResult = service.upsertQuotaPolicy("tenant-a", Map.of(
                "enabled", true,
                "tokenQuotaPerMinute", 100,
                "tokenQuotaPerDay", 1000,
                "tokenQuotaPerMonth", 30000
        )).block();
        Map<String, Object> priceResult = service.upsertModelPrice("gpt-4o-mini", Map.of(
                "inputPer1k", 0.15,
                "outputPer1k", 0.6,
                "enabled", true
        )).block();

        Assertions.assertEquals(100L, quotaResult.get("tokenQuotaPerMinute"));
        Assertions.assertEquals("gpt-4o-mini", priceResult.get("model"));
        Mockito.verify(tenantConfigQueryService, Mockito.times(2)).refreshFromDatabaseReactive();
    }
}
