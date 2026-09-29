package com.nageoffer.shortlink.aigateway.runtime;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayRoutingProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewaySecurityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 域快照的往返与"不许落库的键"两条不变量。
 * <p>
 * 后者比前者重要：一旦 {@code jwtSecret} / {@code users} / {@code providerCredentials} 混进快照，
 * 反序列化时会把 yml 里的密钥与口令清空 —— 这是不会报错、只会在生产上突然失效的那类 bug。
 */
class RuntimeConfigSupportTest {

    @AfterEach
    void clearForceFlag() {
        System.clearProperty(RuntimeConfigSupport.SECURITY_FORCE_ENABLED_KEY);
    }

    private AiGatewayProperties fullyCustomised() {
        AiGatewayProperties properties = new AiGatewayProperties();

        properties.getRouting().setFallbackEnabled(true);
        properties.getRouting().setProviderPriority(List.of("claude", "openai"));
        properties.getRouting().setAbEnabled(true);
        properties.getRouting().setAbProvider("claude");
        properties.getRouting().setAbPercentage(30);
        properties.getRouting().setDynamicRoutingEnabled(true);
        properties.getRouting().setRoutingStrategy(AiGatewayRoutingProperties.RoutingStrategy.LATENCY_OPTIMIZED);
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");
        properties.getUpstream().getModelAlias().put("gpt-4o", "openai:gpt-4o-2024-08-06");

        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setDefaultTokenQuotaPerMinute(1234L);
        properties.getRateLimit().setDefaultTokenQuotaPerDay(5678L);
        properties.getRateLimit().setMinTokenReserve(64L);
        properties.getRateLimit().setQuotaRetryAfterSeconds(90L);
        properties.getRateLimit().setKeyDimensions(List.of("userId"));

        properties.getCache().setEnabled(true);
        properties.getCache().setTtl(Duration.ofMinutes(7));
        properties.getCache().setSemanticCacheEnabled(true);
        properties.getCache().setSemanticSimilarityThreshold(0.9D);
        properties.getCache().setSemanticIndexMaxEntries(321);
        properties.getCache().setSemanticIndexMaxContentLength(1024);

        properties.getSafety().setEnabled(true);
        properties.getSafety().setInputStrategy("audit");
        properties.getSafety().setOutputStrategy("audit");
        properties.getSafety().setBlockedWords(Set.of("badword"));
        properties.getSafety().setPromptInjectionPatterns(List.of("ignore all"));
        properties.getSafety().setPiiPatterns(List.of("\\d{11}"));
        properties.getSafety().setRedactMask("[REDACTED]");

        properties.getPlugin().getPluginEnabledMap().put("pii", false);
        properties.getPlugin().getPluginEnabledMap().put("audit", true);

        properties.getSecurity().setEnabled(false);
        properties.getSecurity().setWriteRoles(List.of("admin", "ops"));

        return properties;
    }

    @Test
    void shouldRoundTripEveryDomain() {
        AiGatewayProperties source = fullyCustomised();
        AiGatewayProperties target = new AiGatewayProperties();

        for (RuntimeConfigDomain domain : RuntimeConfigDomain.values()) {
            Map<String, Object> snapshot = RuntimeConfigSupport.extract(domain, source);
            RuntimeConfigSupport.apply(domain, target, snapshot, false);
        }

        for (RuntimeConfigDomain domain : RuntimeConfigDomain.values()) {
            Assertions.assertEquals(
                    RuntimeConfigSupport.extract(domain, source),
                    RuntimeConfigSupport.extract(domain, target),
                    "domain round trip mismatch: " + domain.key());
        }
    }

    @Test
    void shouldNeverExposeDeploymentSecretsInSnapshots() {
        AiGatewayProperties properties = fullyCustomised();
        properties.getSecurity().setJwtSecret("super-secret-signing-key-value-1234");
        properties.getSecurity().setJwtIssuer("custom-issuer");
        properties.getSecurity().setSessionTtlMinutes(999L);
        properties.getSecurity().getUsers().put("root", new AiGatewaySecurityProperties.UserCredential("p@ss", "admin"));

        Set<String> securityKeys = RuntimeConfigSupport.extract(RuntimeConfigDomain.SECURITY, properties).keySet();

        Assertions.assertEquals(Set.of("enabled", "writeRoles"), securityKeys);
    }

    @Test
    void shouldKeepProviderGroupsAndCredentialsOutOfTheRoutingSnapshot() {
        AiGatewayProperties properties = fullyCustomised();
        properties.getUpstream().getProviderCredentials().put("openai",
                new AiGatewayProperties.ProviderCredential());
        properties.getRouting().getProviderGroups().put("default",
                new AiGatewayRoutingProperties.ProviderGroupConfig());
        properties.getRouting().getModelGroups().put("gpt-4o", "default");

        Set<String> routingKeys = RuntimeConfigSupport.extract(RuntimeConfigDomain.ROUTING, properties).keySet();

        Assertions.assertFalse(routingKeys.contains("providerGroups"));
        Assertions.assertFalse(routingKeys.contains("modelGroups"));
        Assertions.assertFalse(routingKeys.contains("providerCredentials"));
        Assertions.assertFalse(routingKeys.contains("providerChatPath"));
        Assertions.assertFalse(routingKeys.contains("defaultProvider"));
    }

    @Test
    void shouldSerialiseDurationAsSecondsAndEnumAsName() {
        AiGatewayProperties properties = fullyCustomised();

        Map<String, Object> cache = RuntimeConfigSupport.extract(RuntimeConfigDomain.CACHE, properties);
        Assertions.assertEquals(420L, cache.get("ttlSeconds"));

        Map<String, Object> routing = RuntimeConfigSupport.extract(RuntimeConfigDomain.ROUTING, properties);
        Assertions.assertEquals("LATENCY_OPTIMIZED", routing.get("routingStrategy"));
    }

    @Test
    void shouldPreserveYmlValuesForKeysMissingFromSnapshot() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getCache().setEnabled(false);
        properties.getCache().setSemanticIndexMaxEntries(1000);

        // 库里只有 ttl 这一个键（历史版本写入的旧快照）
        RuntimeConfigSupport.apply(RuntimeConfigDomain.CACHE, properties, Map.of("ttlSeconds", 30L), false);

        Assertions.assertEquals(Duration.ofSeconds(30), properties.getCache().getTtl());
        Assertions.assertFalse(properties.getCache().isEnabled());
        Assertions.assertEquals(1000, properties.getCache().getSemanticIndexMaxEntries());
    }

    @Test
    void shouldIgnoreUnknownRoutingStrategyInsteadOfFailingWholeLoad() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getRouting().setRoutingStrategy(AiGatewayRoutingProperties.RoutingStrategy.STATIC);

        RuntimeConfigSupport.apply(RuntimeConfigDomain.ROUTING, properties,
                Map.of("routingStrategy", "NOT_A_REAL_STRATEGY"), false);

        Assertions.assertEquals(AiGatewayRoutingProperties.RoutingStrategy.STATIC,
                properties.getRouting().getRoutingStrategy());
    }

    @Test
    void shouldAllowSafetyWordListToBeEmptiedButNotProviderPriority() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getSafety().setBlockedWords(Set.of("existing"));

        RuntimeConfigSupport.apply(RuntimeConfigDomain.SAFETY, properties, Map.of("blockedWords", List.of()), false);
        Assertions.assertTrue(properties.getSafety().getBlockedWords().isEmpty());

        Map<String, Object> before = RuntimeConfigSupport.extract(RuntimeConfigDomain.ROUTING, properties);
        RuntimeConfigSupport.apply(RuntimeConfigDomain.ROUTING, properties, Map.of("providerPriority", List.of()), false);
        Assertions.assertEquals(before.get("providerPriority"),
                RuntimeConfigSupport.extract(RuntimeConfigDomain.ROUTING, properties).get("providerPriority"));
    }

    @Test
    void shouldForceSecurityEnabledWhenForceFlagIsOn() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getSecurity().setEnabled(false);

        RuntimeConfigSupport.apply(RuntimeConfigDomain.SECURITY, properties, Map.of("enabled", false), true);

        Assertions.assertTrue(properties.getSecurity().isEnabled());
    }

    @Test
    void shouldReadSecurityForceFlagFromSystemProperty() {
        Assertions.assertFalse(RuntimeConfigSupport.securityForceEnabled());

        System.setProperty(RuntimeConfigSupport.SECURITY_FORCE_ENABLED_KEY, "true");
        Assertions.assertTrue(RuntimeConfigSupport.securityForceEnabled());

        System.setProperty(RuntimeConfigSupport.SECURITY_FORCE_ENABLED_KEY, "not-a-boolean");
        Assertions.assertFalse(RuntimeConfigSupport.securityForceEnabled());
    }

    @Test
    void shouldResolveDomainKeyAndRejectUnknownOnes() {
        Assertions.assertEquals(RuntimeConfigDomain.RATE_LIMIT, RuntimeConfigDomain.fromKey("rateLimit"));
        Assertions.assertEquals("security", RuntimeConfigDomain.SECURITY.key());
        Assertions.assertThrows(IllegalArgumentException.class, () -> RuntimeConfigDomain.fromKey("nope"));
    }
}
