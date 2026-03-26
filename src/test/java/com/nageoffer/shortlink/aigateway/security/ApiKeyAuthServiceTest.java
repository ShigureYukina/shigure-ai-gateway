package com.nageoffer.shortlink.aigateway.security;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.Map;

class ApiKeyAuthServiceTest {

    @Test
    void shouldReturnGlobalContextWhenTenantAuthDisabled() {
        AiGatewayProperties properties = baseProperties(false);
        ApiKeyAuthService service = new ApiKeyAuthService(properties);

        TenantContext context = service.authenticate(new HttpHeaders());
        Assertions.assertEquals("global", context.tenantId());
        Assertions.assertEquals("default-app", context.appId());
        Assertions.assertEquals("default-key", context.keyId());
    }

    @Test
    void shouldAuthenticateWithBearerToken() {
        AiGatewayProperties properties = baseProperties(true);
        ApiKeyAuthService service = new ApiKeyAuthService(properties);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("tenant-secret");

        TenantContext context = service.authenticate(headers);
        Assertions.assertEquals("tenant-a", context.tenantId());
        Assertions.assertEquals("app-a", context.appId());
        Assertions.assertEquals("key-a", context.keyId());
    }

    @Test
    void shouldAuthenticateWithXApiKeyHeader() {
        AiGatewayProperties properties = baseProperties(true);
        ApiKeyAuthService service = new ApiKeyAuthService(properties);

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", "header-secret");

        TenantContext context = service.authenticate(headers);
        Assertions.assertEquals("tenant-b", context.tenantId());
        Assertions.assertEquals("app-b", context.appId());
        Assertions.assertEquals("key-b", context.keyId());
    }

    @Test
    void shouldPreferDatabaseCredentialWhenAvailable() {
        AiGatewayProperties properties = baseProperties(true);
        TenantConfigQueryService queryService = Mockito.mock(TenantConfigQueryService.class);
        AiGatewayProperties.TenantApiKeyCredential dbCredential = new AiGatewayProperties.TenantApiKeyCredential();
        dbCredential.setApiKey("db-secret");
        dbCredential.setTenantId("tenant-db");
        dbCredential.setAppId("app-db");
        dbCredential.setKeyId("key-db");
        Mockito.when(queryService.findApiKeyCredential("db-secret")).thenReturn(java.util.Optional.of(dbCredential));
        ApiKeyAuthService service = new ApiKeyAuthService(properties, queryService);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("db-secret");

        TenantContext context = service.authenticate(headers);
        Assertions.assertEquals("tenant-db", context.tenantId());
        Assertions.assertEquals("app-db", context.appId());
        Assertions.assertEquals("key-db", context.keyId());
    }

    @Test
    void shouldRejectMissingInvalidDisabledExpiredOrUnmappedApiKey() {
        AiGatewayProperties properties = baseProperties(true);
        ApiKeyAuthService service = new ApiKeyAuthService(properties);

        AiGatewayClientException missingKey = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.authenticate(new HttpHeaders()));
        Assertions.assertEquals(AiGatewayErrorCode.UNAUTHORIZED, missingKey.getErrorCode());

        HttpHeaders invalidHeaders = new HttpHeaders();
        invalidHeaders.setBearerAuth("unknown-secret");
        AiGatewayClientException invalidKey = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.authenticate(invalidHeaders));
        Assertions.assertEquals(AiGatewayErrorCode.UNAUTHORIZED, invalidKey.getErrorCode());

        HttpHeaders disabledHeaders = new HttpHeaders();
        disabledHeaders.setBearerAuth("disabled-secret");
        AiGatewayClientException disabledKey = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.authenticate(disabledHeaders));
        Assertions.assertEquals(AiGatewayErrorCode.UNAUTHORIZED, disabledKey.getErrorCode());

        HttpHeaders expiredHeaders = new HttpHeaders();
        expiredHeaders.setBearerAuth("expired-secret");
        AiGatewayClientException expiredKey = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.authenticate(expiredHeaders));
        Assertions.assertEquals(AiGatewayErrorCode.UNAUTHORIZED, expiredKey.getErrorCode());

        AiGatewayProperties unmappedProperties = baseProperties(true);
        AiGatewayProperties.TenantApiKeyCredential missingTenantCredential = new AiGatewayProperties.TenantApiKeyCredential();
        missingTenantCredential.setApiKey("missing-tenant-secret");
        missingTenantCredential.setTenantId("");
        missingTenantCredential.setAppId("app-z");
        missingTenantCredential.setKeyId("key-z");
        unmappedProperties.getTenant().setApiKeys(Map.of("key-z", missingTenantCredential));
        ApiKeyAuthService unmappedService = new ApiKeyAuthService(unmappedProperties);
        HttpHeaders unmappedHeaders = new HttpHeaders();
        unmappedHeaders.setBearerAuth("missing-tenant-secret");
        AiGatewayClientException unmappedKey = Assertions.assertThrows(AiGatewayClientException.class,
                () -> unmappedService.authenticate(unmappedHeaders));
        Assertions.assertEquals(AiGatewayErrorCode.UNAUTHORIZED, unmappedKey.getErrorCode());
    }

    private AiGatewayProperties baseProperties(boolean tenantEnabled) {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getTenant().setEnabled(tenantEnabled);
        properties.getTenant().setDefaultTenantId("global");
        properties.getTenant().setDefaultAppId("default-app");
        properties.getTenant().setDefaultKeyId("default-key");

        AiGatewayProperties.TenantApiKeyCredential activeCredential = new AiGatewayProperties.TenantApiKeyCredential();
        activeCredential.setApiKey("tenant-secret");
        activeCredential.setTenantId("tenant-a");
        activeCredential.setAppId("app-a");
        activeCredential.setKeyId("key-a");

        AiGatewayProperties.TenantApiKeyCredential headerCredential = new AiGatewayProperties.TenantApiKeyCredential();
        headerCredential.setApiKey("header-secret");
        headerCredential.setTenantId("tenant-b");
        headerCredential.setAppId("app-b");
        headerCredential.setKeyId("key-b");

        AiGatewayProperties.TenantApiKeyCredential disabledCredential = new AiGatewayProperties.TenantApiKeyCredential();
        disabledCredential.setApiKey("disabled-secret");
        disabledCredential.setTenantId("tenant-c");
        disabledCredential.setAppId("app-c");
        disabledCredential.setKeyId("key-c");
        disabledCredential.setEnabled(false);

        AiGatewayProperties.TenantApiKeyCredential expiredCredential = new AiGatewayProperties.TenantApiKeyCredential();
        expiredCredential.setApiKey("expired-secret");
        expiredCredential.setTenantId("tenant-d");
        expiredCredential.setAppId("app-d");
        expiredCredential.setKeyId("key-d");
        expiredCredential.setExpiresAt(Instant.now().minusSeconds(60));

        properties.getTenant().setApiKeys(Map.of(
                "key-a", activeCredential,
                "key-b", headerCredential,
                "key-c", disabledCredential,
                "key-d", expiredCredential
        ));
        return properties;
    }
}
