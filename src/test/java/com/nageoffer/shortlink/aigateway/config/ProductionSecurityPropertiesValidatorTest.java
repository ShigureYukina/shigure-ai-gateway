package com.nageoffer.shortlink.aigateway.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

class ProductionSecurityPropertiesValidatorTest {

    @Test
    void shouldPassWhenProdSecretsAreValid() {
        AiGatewayProperties properties = buildValidProperties();
        ProductionSecurityPropertiesValidator validator = new ProductionSecurityPropertiesValidator(properties);

        String original = System.getProperty("spring.cloud.nacos.discovery.server-addr");
        try {
            System.setProperty("spring.cloud.nacos.discovery.server-addr", "127.0.0.1:8848");
            Assertions.assertDoesNotThrow(validator::validate);
        } finally {
            restoreProperty("spring.cloud.nacos.discovery.server-addr", original);
        }
    }

    @Test
    void shouldFailWhenJwtSecretInvalid() {
        AiGatewayProperties properties = buildValidProperties();
        properties.getSecurity().setJwtSecret("replace-with-at-least-32-char-secret-key");
        ProductionSecurityPropertiesValidator validator = new ProductionSecurityPropertiesValidator(properties);

        String original = System.getProperty("spring.cloud.nacos.discovery.server-addr");
        try {
            System.setProperty("spring.cloud.nacos.discovery.server-addr", "127.0.0.1:8848");
            IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class, validator::validate);
            Assertions.assertTrue(ex.getMessage().contains("AI_GATEWAY_JWT_SECRET"));
        } finally {
            restoreProperty("spring.cloud.nacos.discovery.server-addr", original);
        }
    }

    @Test
    void shouldFailWhenDefaultPasswordOrMissingNacos() {
        AiGatewayProperties properties = buildValidProperties();
        properties.getSecurity().getUsers().get("viewer").setPassword("viewer123456");
        ProductionSecurityPropertiesValidator validator = new ProductionSecurityPropertiesValidator(properties);

        String original = System.getProperty("spring.cloud.nacos.discovery.server-addr");
        try {
            System.clearProperty("spring.cloud.nacos.discovery.server-addr");
            IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class, validator::validate);
            Assertions.assertTrue(ex.getMessage().contains("viewer") || ex.getMessage().contains("NACOS_SERVER_ADDR"));
        } finally {
            restoreProperty("spring.cloud.nacos.discovery.server-addr", original);
        }
    }

    private AiGatewayProperties buildValidProperties() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getSecurity().setJwtSecret("12345678901234567890123456789012");
        properties.getSecurity().setUsers(Map.of(
                "admin", new AiGatewayProperties.UserCredential("admin-strong-pass", "admin"),
                "viewer", new AiGatewayProperties.UserCredential("viewer-strong-pass", "viewer")
        ));
        return properties;
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
