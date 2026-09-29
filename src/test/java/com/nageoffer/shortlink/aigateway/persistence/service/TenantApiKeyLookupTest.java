package com.nageoffer.shortlink.aigateway.persistence.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.crypto.AesGcmSecretCipher;
import com.nageoffer.shortlink.aigateway.crypto.MasterKey;
import com.nageoffer.shortlink.aigateway.crypto.SecretCipher;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantApiKeyEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * 加密开启后 {@code tenant_api_key} 的读路径。
 * <p>
 * 三条最要紧的：密文行能查出<b>明文</b>凭证（否则上游拿到密文就去打 401）、
 * 未回填的历史明文行仍然能查（迁移窗口内不能断服）、
 * 单行解不开只跳过那一行（一条坏数据不该让所有租户都进不来）。
 */
class TenantApiKeyLookupTest {

    private static final SecretCipher CIPHER = AesGcmSecretCipher.of(masterKey());

    /**
     * 快照只在 persistence 打开时才参与查询，不开就只读 yml（见 DomainLookupSupport）。
     */
    private final AiGatewayProperties properties = persistenceEnabled();

    @Test
    void shouldReturnPlaintextCredentialForEncryptedRow() {
        TenantApiKeyLookup lookup = new TenantApiKeyLookup(properties, null, CIPHER);
        lookup.apply(List.of(row(CIPHER.encrypt("sk-tenant-a"))));

        Assertions.assertEquals(1, lookup.size());
        Assertions.assertEquals("sk-tenant-a",
                lookup.find("sk-tenant-a").orElseThrow().getApiKey(),
                "凭证里的 apiKey 必须是明文：ApiKeyAuthService 要拿它跟请求头比对");
    }

    @Test
    void shouldStillFindLegacyPlaintextRowWhileCipherIsEnabled() {
        TenantApiKeyLookup lookup = new TenantApiKeyLookup(properties, null, CIPHER);
        // 加密刚开启、SecretMigrationRunner 还没回填的行：api_key 仍是明文
        lookup.apply(List.of(row("sk-legacy")));

        Assertions.assertEquals("sk-legacy", lookup.find("sk-legacy").orElseThrow().getApiKey());
    }

    @Test
    void shouldSkipUndecryptableRowWithoutLosingTheOthers() {
        TenantApiKeyLookup lookup = new TenantApiKeyLookup(properties, null, CIPHER);
        TenantApiKeyEntity corrupt = row(AesGcmSecretCipher.PREFIX + "!!!not-base64!!!");

        lookup.apply(List.of(corrupt, row(CIPHER.encrypt("sk-ok"))));

        Assertions.assertEquals(1, lookup.size(), "坏行只跳过自己，不能让整批快照都加载失败");
        Assertions.assertTrue(lookup.find("sk-ok").isPresent());
    }

    @Test
    void shouldKeepWorkingWhenCipherIsDisabled() {
        TenantApiKeyLookup lookup = new TenantApiKeyLookup(properties, null, AesGcmSecretCipher.disabled());
        lookup.apply(List.of(row("sk-plain")));

        Assertions.assertEquals("sk-plain", lookup.find("sk-plain").orElseThrow().getApiKey());
    }

    private static AiGatewayProperties persistenceEnabled() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getTenant().getPersistence().setEnabled(true);
        return properties;
    }

    private TenantApiKeyEntity row(String storedApiKey) {
        TenantApiKeyEntity entity = new TenantApiKeyEntity();
        entity.setId(1L);
        entity.setTenantId("tenant-a");
        entity.setAppId("app-a");
        entity.setKeyId("key-a");
        entity.setApiKey(storedApiKey);
        entity.setEnabled(true);
        entity.setExpiresAt(Instant.parse("2030-01-01T00:00:00Z"));
        return entity;
    }

    private static MasterKey masterKey() {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) 5);
        return MasterKey.fromBase64(Base64.getEncoder().encodeToString(bytes));
    }
}
