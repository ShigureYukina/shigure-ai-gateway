package com.nageoffer.shortlink.aigateway.security;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

class ConsoleAuthServiceTest {

    @Test
    void shouldAuthenticateAsAdminWhenSecurityDisabled() {
        AiGatewayProperties properties = baseProperties(false);
        JwtTokenService jwtTokenService = new JwtTokenService(properties);
        ConsoleAuthService service = new ConsoleAuthService(properties, jwtTokenService);

        ConsoleAuthService.AuthPrincipal principal = service.authenticate(new HttpHeaders());
        Assertions.assertEquals("anonymous", principal.username());
        Assertions.assertEquals("admin", principal.role());
    }

    @Test
    void shouldLoginAndAuthenticateWhenSecurityEnabled() {
        AiGatewayProperties properties = baseProperties(true);
        JwtTokenService jwtTokenService = new JwtTokenService(properties);
        ConsoleAuthService service = new ConsoleAuthService(properties, jwtTokenService);

        ConsoleAuthService.LoginResult loginResult = service.login("admin", "pwd-admin");
        Assertions.assertNotNull(loginResult.token());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Console-Token", loginResult.token());
        ConsoleAuthService.AuthPrincipal principal = service.authenticate(headers);
        Assertions.assertEquals("admin", principal.username());
        Assertions.assertEquals("admin", principal.role());
    }

    @Test
    void shouldRejectMissingTokenOrWrongPasswordOrForbiddenWrite() {
        AiGatewayProperties properties = baseProperties(true);
        JwtTokenService jwtTokenService = new JwtTokenService(properties);
        ConsoleAuthService service = new ConsoleAuthService(properties, jwtTokenService);

        AiGatewayClientException missingToken = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.authenticate(new HttpHeaders()));
        Assertions.assertEquals(AiGatewayErrorCode.UNAUTHORIZED, missingToken.getErrorCode());

        AiGatewayClientException wrongPassword = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.login("admin", "wrong"));
        Assertions.assertEquals(AiGatewayErrorCode.UNAUTHORIZED, wrongPassword.getErrorCode());

        ConsoleAuthService.AuthPrincipal viewer = new ConsoleAuthService.AuthPrincipal("viewer", "viewer");
        AiGatewayClientException forbidden = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.assertWriteAllowed(viewer));
        Assertions.assertEquals(AiGatewayErrorCode.FORBIDDEN, forbidden.getErrorCode());
    }

    @Test
    void shouldAllowWriteWhenSecurityDisabledOrRoleAllowed() {
        AiGatewayProperties disabledProperties = baseProperties(false);
        ConsoleAuthService disabledService = new ConsoleAuthService(disabledProperties, new JwtTokenService(disabledProperties));
        disabledService.assertWriteAllowed(new ConsoleAuthService.AuthPrincipal("any", "viewer"));

        AiGatewayProperties enabledProperties = baseProperties(true);
        ConsoleAuthService enabledService = new ConsoleAuthService(enabledProperties, new JwtTokenService(enabledProperties));
        enabledService.assertWriteAllowed(new ConsoleAuthService.AuthPrincipal("admin", "admin"));
    }

    private AiGatewayProperties baseProperties(boolean securityEnabled) {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getSecurity().setEnabled(securityEnabled);
        properties.getSecurity().setSessionTtlMinutes(60L);
        properties.getSecurity().setJwtIssuer("ai-gateway-test");
        properties.getSecurity().setJwtSecret("12345678901234567890123456789012");
        properties.getSecurity().setUsers(Map.of(
                "admin", new AiGatewayProperties.UserCredential("pwd-admin", "admin"),
                "viewer", new AiGatewayProperties.UserCredential("pwd-viewer", "viewer")
        ));
        properties.getSecurity().setWriteRoles(java.util.List.of("admin"));
        return properties;
    }
}
