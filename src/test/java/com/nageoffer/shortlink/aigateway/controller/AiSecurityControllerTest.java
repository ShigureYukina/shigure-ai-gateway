package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.audit.AuditLogService;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.security.ConsoleAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class AiSecurityControllerTest {

    private AiGatewayProperties properties;
    private ConsoleAuthService consoleAuthService;
    private AuditLogService auditLogService;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        properties = new AiGatewayProperties();
        consoleAuthService = Mockito.mock(ConsoleAuthService.class);
        auditLogService = Mockito.mock(AuditLogService.class);
        webTestClient = WebTestClient.bindToController(new AiSecurityController(properties, consoleAuthService, auditLogService)).build();
    }

    @Test
    void shouldReturnLocalDevTokenWhenSecurityDisabled() {
        properties.getSecurity().setEnabled(false);

        webTestClient.post()
                .uri("/v1/security/login")
                .bodyValue(Map.of("username", "admin", "password", "any"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.token").isEqualTo("local-dev")
                .jsonPath("$.securityEnabled").isEqualTo(false);
    }

    @Test
    void shouldLoginConfigUpdateAndLogoutWhenSecurityEnabled() {
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().setWriteRoles(List.of("admin"));

        Mockito.when(consoleAuthService.login("admin", "pwd-admin"))
                .thenReturn(new ConsoleAuthService.LoginResult("token-1", "admin", "admin", Instant.parse("2030-01-01T00:00:00Z")));
        Mockito.when(consoleAuthService.authenticate(any()))
                .thenReturn(new ConsoleAuthService.AuthPrincipal("admin", "admin"));

        webTestClient.post()
                .uri("/v1/security/login")
                .bodyValue(Map.of("username", "admin", "password", "pwd-admin"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.token").isEqualTo("token-1")
                .jsonPath("$.role").isEqualTo("admin");

        webTestClient.get()
                .uri("/v1/security/config")
                .header("X-Console-Token", "token-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.currentUser").isEqualTo("admin");

        webTestClient.post()
                .uri("/v1/security/config")
                .header("X-Console-Token", "token-1")
                .bodyValue(Map.of("enabled", true, "writeRoles", List.of("admin", "ops")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.writeRoles.length()").isEqualTo(2)
                .jsonPath("$.currentRole").isEqualTo("admin");

        webTestClient.post()
                .uri("/v1/security/logout")
                .header("X-Console-Token", "token-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true);

        Mockito.verify(consoleAuthService).logout(eq("token-1"));
    }

    @Test
    void shouldRejectBlankLoginRequestWhenSecurityEnabled() {
        properties.getSecurity().setEnabled(true);

        webTestClient.post()
                .uri("/v1/security/login")
                .bodyValue(Map.of("username", "", "password", ""))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.message").isEqualTo("请输入用户名与密码");
    }
}
