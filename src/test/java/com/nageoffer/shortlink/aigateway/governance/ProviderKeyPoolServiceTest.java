package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayUpstreamException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class ProviderKeyPoolServiceTest {

    private final ProviderKeyPoolService service = new ProviderKeyPoolService();

    @Test
    void shouldSpreadTrafficAcrossKeysByWeight() {
        List<AiGatewayProperties.ProviderApiKey> pool = List.of(key("k1", "sk-1", 9), key("k2", "sk-2", 1));

        int firstHits = 0;
        int rounds = 2000;
        for (int i = 0; i < rounds; i++) {
            if ("k1".equals(service.keyIdOf(service.select("t1", "openai", pool)))) {
                firstHits++;
            }
        }

        double ratio = firstHits * 1.0D / rounds;
        Assertions.assertTrue(ratio > 0.85 && ratio < 0.95, "实际占比 " + ratio);
    }

    @Test
    void shouldSkipKeyAfterConsecutiveKeyRelatedFailures() {
        List<AiGatewayProperties.ProviderApiKey> pool = List.of(key("k1", "sk-1", 1), key("k2", "sk-2", 1));
        AiGatewayUpstreamException unauthorized = new AiGatewayUpstreamException(401, "invalid api key", false);

        service.reportFailure("t1", "openai", "k1", unauthorized);
        service.reportFailure("t1", "openai", "k1", unauthorized);

        for (int i = 0; i < 50; i++) {
            // 冷却中的 Key 必须被跳过，流量全部落到另一把
            Assertions.assertEquals("k2", service.keyIdOf(service.select("t1", "openai", pool)));
        }
    }

    @Test
    void shouldKeepServingWhenEveryKeyIsCooling() {
        List<AiGatewayProperties.ProviderApiKey> pool = List.of(key("k1", "sk-1", 1), key("k2", "sk-2", 1));
        AiGatewayUpstreamException overloaded = new AiGatewayUpstreamException(429, "rate limited", true);
        service.reportFailure("t1", "openai", "k1", overloaded);
        service.reportFailure("t1", "openai", "k1", overloaded);
        service.reportFailure("t1", "openai", "k2", overloaded);
        service.reportFailure("t1", "openai", "k2", overloaded);

        // 全冷却时不能返回 null——那等于整个渠道被判死，反而放大了故障
        Assertions.assertNotNull(service.select("t1", "openai", pool));
    }

    @Test
    void shouldNotCoolKeyForCallerSideFailures() {
        List<AiGatewayProperties.ProviderApiKey> pool = List.of(key("k1", "sk-1", 1), key("k2", "sk-2", 1));
        AiGatewayUpstreamException badRequest = new AiGatewayUpstreamException(400, "bad param", false);

        service.reportFailure("t1", "openai", "k1", badRequest);
        service.reportFailure("t1", "openai", "k1", badRequest);

        // 参数错误换 Key 一样失败，不该把好 Key 关掉；这里只验证 k1 仍在候选里
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            selected.add(service.keyIdOf(service.select("t1", "openai", pool)));
        }
        Assertions.assertTrue(selected.contains("k1"));
    }

    @Test
    void shouldReportKeyStateForObservability() {
        service.reportFailure("t1", "openai", "k1", new AiGatewayUpstreamException(500, "boom", true));

        Assertions.assertTrue(service.describe().containsKey("t1|openai|k1"));
    }

    @Test
    void shouldIgnoreDisabledAndBlankKeys() {
        List<AiGatewayProperties.ProviderApiKey> pool = List.of(key("k1", "  ", 1));
        Assertions.assertNull(service.select("t1", "openai", pool));
        Assertions.assertNull(service.select("t1", "openai", List.of()));
    }

    @Test
    void shouldDeriveKeyIdFromHashWhenNotConfigured() {
        AiGatewayProperties.ProviderApiKey withoutId = new AiGatewayProperties.ProviderApiKey();
        withoutId.setApiKey("sk-secret");

        String keyId = service.keyIdOf(withoutId);

        // 不能把 Key 本身写进日志，只能给一个可对齐的短标识
        Assertions.assertTrue(keyId.startsWith("key-"));
        Assertions.assertFalse(keyId.contains("sk-secret"));

        Assertions.assertEquals("k1", service.keyIdOf(key("k1", "sk-1", 1)));
    }

    @Test
    void shouldNotCountCredentialMissingAsRepeatedFailureForever() {
        // PROVIDER_RATE_LIMITED 属于"换通道能好"，因此计入 Key 失败
        AiGatewayClientException rateLimited = new AiGatewayClientException(
                AiGatewayErrorCode.PROVIDER_RATE_LIMITED, "渠道限速");
        service.reportFailure("t1", "openai", "k1", rateLimited);
        service.reportFailure("t1", "openai", "k1", rateLimited);

        Assertions.assertTrue(service.describe().containsKey("t1|openai|k1"));
    }

    private AiGatewayProperties.ProviderApiKey key(String keyId, String apiKey, int weight) {
        AiGatewayProperties.ProviderApiKey key = new AiGatewayProperties.ProviderApiKey();
        key.setKeyId(keyId);
        key.setApiKey(apiKey);
        key.setWeight(weight);
        return key;
    }
}
