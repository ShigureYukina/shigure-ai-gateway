package com.nageoffer.shortlink.aigateway.config;

import com.nageoffer.shortlink.aigateway.security.PasswordEncoderSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

class ProductionSecurityPropertiesValidatorTest {

    private static final PasswordEncoderSupport ENCODER = new PasswordEncoderSupport();

    @Test
    void shouldPassWhenProdSecretsAreValid() {
        AiGatewayProperties properties = buildValidProperties();
        ProductionSecurityPropertiesValidator validator = new ProductionSecurityPropertiesValidator(properties, ENCODER);

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
        ProductionSecurityPropertiesValidator validator = new ProductionSecurityPropertiesValidator(properties, ENCODER);

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
        properties.getSecurity().getUsers().get("viewer").setPassword(ENCODER.encode("viewer123456"));
        ProductionSecurityPropertiesValidator validator = new ProductionSecurityPropertiesValidator(properties, ENCODER);

        String original = System.getProperty("spring.cloud.nacos.discovery.server-addr");
        try {
            System.clearProperty("spring.cloud.nacos.discovery.server-addr");
            IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class, validator::validate);
            Assertions.assertTrue(ex.getMessage().contains("viewer") || ex.getMessage().contains("NACOS_SERVER_ADDR"));
        } finally {
            restoreProperty("spring.cloud.nacos.discovery.server-addr", original);
        }
    }

    @Test
    void shouldFailWhenPasswordIsStillPlaintext() {
        AiGatewayProperties properties = buildValidProperties();
        properties.getSecurity().getUsers().get("admin")
                .setPassword("a-very-strong-but-plaintext-pass");
        ProductionSecurityPropertiesValidator validator = new ProductionSecurityPropertiesValidator(properties, ENCODER);

        String original = System.getProperty("spring.cloud.nacos.discovery.server-addr");
        try {
            System.setProperty("spring.cloud.nacos.discovery.server-addr", "127.0.0.1:8848");
            IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class, validator::validate);
            Assertions.assertTrue(ex.getMessage().contains("{bcrypt}"));
        } finally {
            restoreProperty("spring.cloud.nacos.discovery.server-addr", original);
        }
    }

    @Test
    void shouldFailWhenMasterKeyMissingOrInvalid() {
        AiGatewayProperties missing = buildValidProperties();
        missing.getSecurity().getSecret().setMasterKey(null);
        Assertions.assertTrue(Assertions.assertThrows(IllegalStateException.class,
                () -> new ProductionSecurityPropertiesValidator(missing, ENCODER).validate())
                .getMessage().contains("AI_GATEWAY_MASTER_KEY"));

        AiGatewayProperties notBase64 = buildValidProperties();
        notBase64.getSecurity().getSecret().setMasterKey("not base64!!");
        Assertions.assertTrue(Assertions.assertThrows(IllegalStateException.class,
                () -> new ProductionSecurityPropertiesValidator(notBase64, ENCODER).validate())
                .getMessage().contains("不合法"));

        // 长度不对同样要拦住：静默降级成明文是最坏的结果
        AiGatewayProperties tooShort = buildValidProperties();
        tooShort.getSecurity().getSecret().setMasterKey(
                java.util.Base64.getEncoder().encodeToString(new byte[16]));
        Assertions.assertTrue(Assertions.assertThrows(IllegalStateException.class,
                () -> new ProductionSecurityPropertiesValidator(tooShort, ENCODER).validate())
                .getMessage().contains("不合法"));
    }

    private AiGatewayProperties buildValidProperties() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getSecurity().setJwtSecret("12345678901234567890123456789012");
        properties.getSecurity().getSecret().setMasterKey(
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
        properties.getSecurity().setUsers(Map.of(
                "admin", new AiGatewaySecurityProperties.UserCredential(ENCODER.encode("admin-strong-pass"), "admin"),
                "viewer", new AiGatewaySecurityProperties.UserCredential(ENCODER.encode("viewer-strong-pass"), "viewer")
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
