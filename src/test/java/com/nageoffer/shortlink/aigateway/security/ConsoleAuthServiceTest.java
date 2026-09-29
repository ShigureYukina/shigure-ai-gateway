package com.nageoffer.shortlink.aigateway.security;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewaySecurityProperties;
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

    @Test
    void shouldLoginWithBcryptHashedPassword() {
        PasswordEncoderSupport encoder = new PasswordEncoderSupport();
        AiGatewayProperties properties = baseProperties(true);
        properties.getSecurity().setUsers(Map.of(
                "admin", new AiGatewaySecurityProperties.UserCredential(encoder.encode("pwd-admin"), "admin")
        ));
        ConsoleAuthService service = new ConsoleAuthService(properties, new JwtTokenService(properties), encoder);

        Assertions.assertNotNull(service.login("admin", "pwd-admin").token());
        AiGatewayClientException wrong = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.login("admin", "pwd-admin "));
        Assertions.assertEquals(AiGatewayErrorCode.UNAUTHORIZED, wrong.getErrorCode());
    }

    @Test
    void shouldRejectUnknownUserAndBlankPassword() {
        AiGatewayProperties properties = baseProperties(true);
        ConsoleAuthService service = new ConsoleAuthService(properties, new JwtTokenService(properties));

        Assertions.assertEquals(AiGatewayErrorCode.UNAUTHORIZED,
                Assertions.assertThrows(AiGatewayClientException.class, () -> service.login("nobody", "any")).getErrorCode());
        Assertions.assertEquals(AiGatewayErrorCode.UNAUTHORIZED,
                Assertions.assertThrows(AiGatewayClientException.class, () -> service.login("admin", "")).getErrorCode());
    }

    private AiGatewayProperties baseProperties(boolean securityEnabled) {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getSecurity().setEnabled(securityEnabled);
        properties.getSecurity().setSessionTtlMinutes(60L);
        properties.getSecurity().setJwtIssuer("ai-gateway-test");
        properties.getSecurity().setJwtSecret("12345678901234567890123456789012");
        properties.getSecurity().setUsers(Map.of(
                "admin", new AiGatewaySecurityProperties.UserCredential("pwd-admin", "admin"),
                "viewer", new AiGatewaySecurityProperties.UserCredential("pwd-viewer", "viewer")
        ));
        properties.getSecurity().setWriteRoles(java.util.List.of("admin"));
        return properties;
    }
}
