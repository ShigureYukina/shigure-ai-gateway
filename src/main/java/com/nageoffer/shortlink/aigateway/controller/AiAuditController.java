package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.audit.AuditLogService;
import com.nageoffer.shortlink.aigateway.security.ConsoleAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/audit")
@Tag(name = "审计日志", description = "服务端操作审计")
public class AiAuditController {

    private final ConsoleAuthService consoleAuthService;

    private final AuditLogService auditLogService;

    @Operation(summary = "查询审计日志")
    @GetMapping("/logs")
    public Map<String, Object> logs(@RequestParam(value = "limit", defaultValue = "100") int limit,
                                    ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        ConsoleAuthService.AuthPrincipal principal = consoleAuthService.authenticate(headers);
        return Map.of(
                "items", auditLogService.list(limit),
                "actor", principal.username()
        );
    }

    @Operation(summary = "清空审计日志")
    @PostMapping("/logs/clear")
    public Map<String, Object> clear(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        ConsoleAuthService.AuthPrincipal principal = consoleAuthService.authenticate(headers);
        consoleAuthService.assertWriteAllowed(principal);
        auditLogService.clear();
        auditLogService.record(principal.username(), principal.role(), "AUDIT_CLEAR", "/v1/audit/logs/clear", true, "清空审计日志");
        return Map.of("success", true);
    }
}
