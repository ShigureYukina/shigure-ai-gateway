package com.nageoffer.shortlink.aigateway.security;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    @Test
    void shouldIssueAndParseToken() {
        AiGatewayProperties properties = buildSecurityProperties();
        JwtTokenService service = new JwtTokenService(properties);

        String token = service.issue("alice", "admin");
        JwtTokenService.JwtPrincipal principal = service.parse(token);

        Assertions.assertEquals("alice", principal.username());
        Assertions.assertEquals("admin", principal.role());
        Assertions.assertNotNull(principal.expiresAt());
    }

    @Test
    void shouldFailWhenSecretTooShort() {
        AiGatewayProperties properties = buildSecurityProperties();
        properties.getSecurity().setJwtSecret("short-secret");
        JwtTokenService service = new JwtTokenService(properties);

        AiGatewayClientException ex = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.issue("alice", "admin"));
        Assertions.assertEquals(AiGatewayErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void shouldFailWhenTokenTampered() {
        AiGatewayProperties properties = buildSecurityProperties();
        JwtTokenService service = new JwtTokenService(properties);

        String token = service.issue("alice", "admin");
        String tampered = token + "x";

        AiGatewayClientException ex = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.parse(tampered));
        Assertions.assertEquals(AiGatewayErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    private AiGatewayProperties buildSecurityProperties() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().setSessionTtlMinutes(60L);
        properties.getSecurity().setJwtIssuer("ai-gateway-test");
        properties.getSecurity().setJwtSecret("12345678901234567890123456789012");
        return properties;
    }
}
