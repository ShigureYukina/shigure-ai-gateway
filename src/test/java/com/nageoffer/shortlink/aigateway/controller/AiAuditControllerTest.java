package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.audit.AuditLogEntry;
import com.nageoffer.shortlink.aigateway.audit.AuditLogService;
import com.nageoffer.shortlink.aigateway.security.ConsoleAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class AiAuditControllerTest {

    private ConsoleAuthService consoleAuthService;
    private AuditLogService auditLogService;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        consoleAuthService = Mockito.mock(ConsoleAuthService.class);
        auditLogService = Mockito.mock(AuditLogService.class);
        webTestClient = WebTestClient.bindToController(new AiAuditController(consoleAuthService, auditLogService)).build();
    }

    @Test
    void shouldReturnAuditLogs() {
        Mockito.when(consoleAuthService.authenticate(any()))
                .thenReturn(new ConsoleAuthService.AuthPrincipal("alice", "admin"));
        Mockito.when(auditLogService.list(10))
                .thenReturn(List.of(AuditLogEntry.builder()
                        .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                        .actor("alice")
                        .role("admin")
                        .action("UPDATE")
                        .target("/v1/routing/config")
                        .success(true)
                        .detail("ok")
                        .build()));

        webTestClient.get()
                .uri("/v1/audit/logs?limit=10")
                .header("X-Console-Token", "token-a")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.actor").isEqualTo("alice")
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].action").isEqualTo("UPDATE");
    }

    @Test
    void shouldClearAuditLogs() {
        ConsoleAuthService.AuthPrincipal principal = new ConsoleAuthService.AuthPrincipal("alice", "admin");
        Mockito.when(consoleAuthService.authenticate(any())).thenReturn(principal);

        webTestClient.post()
                .uri("/v1/audit/logs/clear")
                .header("X-Console-Token", "token-a")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true);

        Mockito.verify(consoleAuthService).assertWriteAllowed(eq(principal));
        Mockito.verify(auditLogService).clear();
        Mockito.verify(auditLogService).record(eq("alice"), eq("admin"), eq("AUDIT_CLEAR"), eq("/v1/audit/logs/clear"), eq(true), eq("清空审计日志"));
    }
}
