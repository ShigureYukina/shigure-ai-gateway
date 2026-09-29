package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.governance.ProviderKeyPoolService;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayUpstreamException;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;
import java.util.Optional;

class UpstreamCredentialServiceTest {

    private AiGatewayProperties properties;
    private UpstreamCredentialService service;

    @BeforeEach
    void setUp() {
        properties = new AiGatewayProperties();
        service = new UpstreamCredentialService(TenantConfigQueryService.fallbackOnly(properties), new ProviderKeyPoolService());
    }

    @Test
    void shouldPreferTenantByokOverPlatformCredential() {
        platformCredential("openai", "sk-platform");
        tenantCredential("tenant-a", "openai", "sk-tenant-byok");

        Optional<AiGatewayProperties.ProviderCredential> resolved = service.resolve("tenant-a", "openai");

        Assertions.assertTrue(resolved.isPresent());
        Assertions.assertEquals("sk-tenant-byok", resolved.get().getApiKey());
    }

    @Test
    void shouldFallbackToPlatformCredentialWhenTenantHasNone() {
        platformCredential("openai", "sk-platform");

        Optional<AiGatewayProperties.ProviderCredential> resolved = service.resolve("tenant-b", "openai");

        Assertions.assertTrue(resolved.isPresent());
        Assertions.assertEquals("sk-platform", resolved.get().getApiKey());
    }

    @Test
    void shouldTreatDisabledOrBlankCredentialAsMissing() {
        AiGatewayProperties.ProviderCredential disabled = platformCredential("openai", "sk-platform");
        disabled.setEnabled(false);
        Assertions.assertTrue(service.resolve("tenant-a", "openai").isEmpty());

        AiGatewayProperties.ProviderCredential blank = platformCredential("claude", "");
        Assertions.assertTrue(blank.isEnabled());
        Assertions.assertTrue(service.resolve("tenant-a", "claude").isEmpty());
    }

    @Test
    void shouldFailFastWithCredentialMissingErrorCode() {
        AiGatewayClientException exception = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.require("tenant-a", "openai"));

        Assertions.assertEquals(AiGatewayErrorCode.UPSTREAM_CREDENTIAL_MISSING, exception.getErrorCode());
    }

    @Test
    void shouldInjectAuthHeaderSchemeAndExtraHeaders() {
        AiGatewayProperties.ProviderCredential credential = tenantCredential("tenant-a", "openai", "sk-tenant");
        credential.setAuthHeader("x-api-key");
        credential.setAuthScheme("");
        credential.setExtraHeaders(Map.of("anthropic-version", "2023-06-01"));
        HttpHeaders headers = new HttpHeaders();

        service.applyCredential(headers, "openai", "tenant-a");

        Assertions.assertEquals("sk-tenant", headers.getFirst("x-api-key"));
        Assertions.assertNull(headers.getFirst(HttpHeaders.AUTHORIZATION));
        Assertions.assertEquals("2023-06-01", headers.getFirst("anthropic-version"));
    }

    private AiGatewayProperties.ProviderCredential platformCredential(String provider, String apiKey) {
        AiGatewayProperties.ProviderCredential credential = credential(apiKey);
        properties.getUpstream().getProviderCredentials().put(provider, credential);
        return credential;
    }

    private AiGatewayProperties.ProviderCredential tenantCredential(String tenantId, String provider, String apiKey) {
        AiGatewayProperties.ProviderCredential credential = credential(apiKey);
        properties.getTenant().getProviderCredentials()
                .computeIfAbsent(tenantId, key -> new java.util.HashMap<>())
                .put(provider, credential);
        return credential;
    }

    @Test
    void shouldUseSingleKeyWhenPoolIsEmpty() {
        platformCredential("openai", "sk-single");

        HttpHeaders headers = new HttpHeaders();
        UpstreamCredentialService.CredentialLease lease = service.applyCredential(headers, "openai", "tenant-a");

        Assertions.assertEquals("Bearer sk-single", headers.getFirst(HttpHeaders.AUTHORIZATION));
        Assertions.assertEquals("primary", lease.keyId());
        Assertions.assertEquals("openai", lease.provider());
    }

    @Test
    void shouldRotateAcrossKeyPoolAndCarryLeaseKeyId() {
        AiGatewayProperties.ProviderCredential credential = platformCredential("openai", "sk-unused");
        credential.setApiKeys(java.util.List.of(poolKey("k1", "sk-1"), poolKey("k2", "sk-2")));

        java.util.Set<String> usedKeys = new java.util.HashSet<>();
        java.util.Set<String> usedKeyIds = new java.util.HashSet<>();
        for (int i = 0; i < 40; i++) {
            HttpHeaders headers = new HttpHeaders();
            UpstreamCredentialService.CredentialLease lease = service.applyCredential(headers, "openai", "tenant-a");
            usedKeys.add(headers.getFirst(HttpHeaders.AUTHORIZATION));
            usedKeyIds.add(lease.keyId());
        }

        // 配了池就以池为准：单 Key 的 apiKey 不再参与
        Assertions.assertTrue(usedKeys.contains("Bearer sk-1"));
        Assertions.assertTrue(usedKeys.contains("Bearer sk-2"));
        Assertions.assertFalse(usedKeys.contains("Bearer sk-unused"));
        Assertions.assertEquals(java.util.Set.of("k1", "k2"), usedKeyIds);
    }

    @Test
    void shouldPushBadKeyToCoolingAfterReportedFailures() {
        AiGatewayProperties.ProviderCredential credential = platformCredential("openai", "sk-unused");
        credential.setApiKeys(java.util.List.of(poolKey("k1", "sk-1"), poolKey("k2", "sk-2")));

        AiGatewayUpstreamException unauthorized = new AiGatewayUpstreamException(401, "invalid key", false);
        UpstreamCredentialService.CredentialLease k1Lease =
                new UpstreamCredentialService.CredentialLease("tenant-a", "openai", "k1");
        service.reportOutcome(k1Lease, false, unauthorized);
        service.reportOutcome(k1Lease, false, unauthorized);

        // 冷却后同一渠道内不会再用这把 Key，失败被隔离在"一把钥匙"而不是整个渠道
        for (int i = 0; i < 20; i++) {
            HttpHeaders headers = new HttpHeaders();
            UpstreamCredentialService.CredentialLease lease = service.applyCredential(headers, "openai", "tenant-a");
            Assertions.assertNotEquals("k1", lease.keyId());
        }
    }

    private AiGatewayProperties.ProviderApiKey poolKey(String keyId, String apiKey) {
        AiGatewayProperties.ProviderApiKey key = new AiGatewayProperties.ProviderApiKey();
        key.setKeyId(keyId);
        key.setApiKey(apiKey);
        return key;
    }

    private AiGatewayProperties.ProviderCredential credential(String apiKey) {
        AiGatewayProperties.ProviderCredential credential = new AiGatewayProperties.ProviderCredential();
        credential.setApiKey(apiKey);
        return credential;
    }
}
