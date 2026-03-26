package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.audit.AuditLogService;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigManagementService;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import com.nageoffer.shortlink.aigateway.security.ConsoleAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class AiTenantConfigControllerTest {

    private ConsoleAuthService consoleAuthService;
    private AuditLogService auditLogService;
    private TenantConfigQueryService tenantConfigQueryService;
    private TenantConfigManagementService tenantConfigManagementService;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        consoleAuthService = Mockito.mock(ConsoleAuthService.class);
        auditLogService = Mockito.mock(AuditLogService.class);
        tenantConfigQueryService = Mockito.mock(TenantConfigQueryService.class);
        tenantConfigManagementService = Mockito.mock(TenantConfigManagementService.class);
        Mockito.when(consoleAuthService.authenticate(any()))
                .thenReturn(new ConsoleAuthService.AuthPrincipal("admin", "admin"));
        webTestClient = WebTestClient.bindToController(new AiTenantConfigController(
                        consoleAuthService,
                        auditLogService,
                        tenantConfigQueryService,
                        tenantConfigManagementService))
                .controllerAdvice(new AiGatewayExceptionHandler())
                .build();
    }

    @Test
    void shouldReadApiKeyAndModelPolicy() {
        AiGatewayProperties.TenantApiKeyCredential credential = new AiGatewayProperties.TenantApiKeyCredential();
        credential.setTenantId("tenant-a");
        credential.setAppId("app-a");
        credential.setKeyId("key-a");
        credential.setEnabled(true);
        credential.setExpiresAt(Instant.parse("2030-01-01T00:00:00Z"));
        Mockito.when(tenantConfigQueryService.findApiKeyCredential("secret-a")).thenReturn(Optional.of(credential));

        AiGatewayProperties.TenantModelPolicy policy = new AiGatewayProperties.TenantModelPolicy();
        policy.setEnabled(true);
        policy.setAllowedModels(java.util.Set.of("gpt-4o-mini-compatible"));
        policy.setModelMappings(Map.of("default", "gpt-4o-mini-compatible"));
        policy.setDefaultModelAlias("default");
        policy.setDefaultModel("gpt-4o-mini-compatible");
        Mockito.when(tenantConfigQueryService.findModelPolicy("tenant-a")).thenReturn(Optional.of(policy));
        Mockito.when(tenantConfigManagementService.getTenant("tenant-a")).thenReturn(Mono.just(Map.of(
                "found", true,
                "tenantId", "tenant-a",
                "tenantName", "Tenant A",
                "tenantStatus", "ACTIVE",
                "description", "desc-a"
        )));
        Mockito.when(tenantConfigManagementService.getTenantApp("tenant-a", "app-a")).thenReturn(Mono.just(Map.of(
                "found", true,
                "tenantId", "tenant-a",
                "appId", "app-a",
                "appName", "App A",
                "appStatus", "ACTIVE",
                "description", "app-desc"
        )));
        Mockito.when(tenantConfigManagementService.listTenantApps("tenant-a")).thenReturn(reactor.core.publisher.Flux.just(Map.of(
                "tenantId", "tenant-a",
                "appId", "app-a",
                "appName", "App A",
                "appStatus", "ACTIVE",
                "description", "app-desc"
        )));

        webTestClient.get()
                .uri("/v1/tenant-config/api-keys/secret-a")
                .header("X-Console-Token", "token-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantId").isEqualTo("tenant-a")
                .jsonPath("$.currentUser").isEqualTo("admin");

        webTestClient.get()
                .uri("/v1/tenant-config/tenants/tenant-a")
                .header("X-Console-Token", "token-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantName").isEqualTo("Tenant A")
                .jsonPath("$.currentUser").isEqualTo("admin");

        webTestClient.get()
                .uri("/v1/tenant-config/tenants/tenant-a/apps/app-a")
                .header("X-Console-Token", "token-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.appName").isEqualTo("App A")
                .jsonPath("$.currentUser").isEqualTo("admin");

        webTestClient.get()
                .uri("/v1/tenant-config/tenants/tenant-a/apps")
                .header("X-Console-Token", "token-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(1)
                .jsonPath("$.items[0].appId").isEqualTo("app-a")
                .jsonPath("$.currentUser").isEqualTo("admin");

        webTestClient.get()
                .uri("/v1/tenant-config/model-policies/tenant-a")
                .header("X-Console-Token", "token-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.defaultModel").isEqualTo("gpt-4o-mini-compatible")
                .jsonPath("$.currentUser").isEqualTo("admin");
    }

    @Test
    void shouldWriteTenantConfigAndAudit() {
        Mockito.when(tenantConfigManagementService.upsertTenant(any())).thenReturn(Mono.just(Map.of(
                "tenantId", "tenant-a", "tenantName", "Tenant A", "tenantStatus", "ACTIVE"
        )));
        Mockito.when(tenantConfigManagementService.upsertTenantApp(eq("tenant-a"), any())).thenReturn(Mono.just(Map.of(
                "tenantId", "tenant-a", "appId", "app-a", "appName", "App A", "appStatus", "ACTIVE"
        )));
        Mockito.when(tenantConfigManagementService.upsertApiKey(any())).thenReturn(Mono.just(Map.of(
                "tenantId", "tenant-a", "appId", "app-a", "keyId", "key-a", "enabled", true
        )));
        Mockito.when(tenantConfigManagementService.upsertQuotaPolicy(eq("tenant-a"), any())).thenReturn(Mono.just(Map.of(
                "tenantId", "tenant-a", "enabled", true, "tokenQuotaPerMinute", 100L, "tokenQuotaPerDay", 1000L, "tokenQuotaPerMonth", 30000L
        )));

        webTestClient.post()
                .uri("/v1/tenant-config/tenants")
                .header("X-Console-Token", "token-1")
                .bodyValue(Map.of("tenantId", "tenant-a", "tenantName", "Tenant A", "tenantStatus", "ACTIVE"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantName").isEqualTo("Tenant A");

        webTestClient.post()
                .uri("/v1/tenant-config/tenants/tenant-a/apps")
                .header("X-Console-Token", "token-1")
                .bodyValue(Map.of("appId", "app-a", "appName", "App A", "appStatus", "ACTIVE"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.appName").isEqualTo("App A");

        webTestClient.post()
                .uri("/v1/tenant-config/api-keys")
                .header("X-Console-Token", "token-1")
                .bodyValue(Map.of("tenantId", "tenant-a", "appId", "app-a", "keyId", "key-a", "apiKey", "secret-a"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantId").isEqualTo("tenant-a");

        webTestClient.post()
                .uri("/v1/tenant-config/quota-policies/tenant-a")
                .header("X-Console-Token", "token-1")
                .bodyValue(Map.of("enabled", true, "tokenQuotaPerMinute", 100, "tokenQuotaPerDay", 1000, "tokenQuotaPerMonth", 30000))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tokenQuotaPerMinute").isEqualTo(100);

        Mockito.verify(consoleAuthService, Mockito.times(4)).assertWriteAllowed(any());
        Mockito.verify(auditLogService, Mockito.times(4)).record(eq("admin"), eq("admin"), any(), any(), eq(true), any());
    }

    @Test
    void shouldDeleteTenantConfigAndAudit() {
        Mockito.when(tenantConfigManagementService.deleteTenant("tenant-a")).thenReturn(Mono.just(Map.of("tenantId", "tenant-a", "deleted", true)));
        Mockito.when(tenantConfigManagementService.deleteTenantApp("tenant-a", "app-a")).thenReturn(Mono.just(Map.of("tenantId", "tenant-a", "appId", "app-a", "deleted", true)));
        Mockito.when(tenantConfigManagementService.deleteApiKey("secret-a")).thenReturn(Mono.just(Map.of("apiKey", "secret-a", "deleted", true)));
        Mockito.when(tenantConfigManagementService.deleteModelPolicy("tenant-a")).thenReturn(Mono.just(Map.of("tenantId", "tenant-a", "deleted", true)));
        Mockito.when(tenantConfigManagementService.deleteQuotaPolicy("tenant-a")).thenReturn(Mono.just(Map.of("tenantId", "tenant-a", "deleted", true)));
        Mockito.when(tenantConfigManagementService.deleteModelPrice("gpt-4o-mini")).thenReturn(Mono.just(Map.of("model", "gpt-4o-mini", "deleted", true)));

        webTestClient.delete().uri("/v1/tenant-config/tenants/tenant-a").header("X-Console-Token", "token-1").exchange().expectStatus().isOk().expectBody().jsonPath("$.deleted").isEqualTo(true);
        webTestClient.delete().uri("/v1/tenant-config/tenants/tenant-a/apps/app-a").header("X-Console-Token", "token-1").exchange().expectStatus().isOk().expectBody().jsonPath("$.appId").isEqualTo("app-a");
        webTestClient.delete().uri("/v1/tenant-config/api-keys/secret-a").header("X-Console-Token", "token-1").exchange().expectStatus().isOk().expectBody().jsonPath("$.apiKey").isEqualTo("secret-a");
        webTestClient.delete().uri("/v1/tenant-config/model-policies/tenant-a").header("X-Console-Token", "token-1").exchange().expectStatus().isOk().expectBody().jsonPath("$.deleted").isEqualTo(true);
        webTestClient.delete().uri("/v1/tenant-config/quota-policies/tenant-a").header("X-Console-Token", "token-1").exchange().expectStatus().isOk().expectBody().jsonPath("$.deleted").isEqualTo(true);
        webTestClient.delete().uri("/v1/tenant-config/model-prices/gpt-4o-mini").header("X-Console-Token", "token-1").exchange().expectStatus().isOk().expectBody().jsonPath("$.model").isEqualTo("gpt-4o-mini");

        Mockito.verify(consoleAuthService, Mockito.times(6)).assertWriteAllowed(any());
        Mockito.verify(auditLogService, Mockito.times(6)).record(eq("admin"), eq("admin"), any(), any(), eq(true), any());
    }
}
