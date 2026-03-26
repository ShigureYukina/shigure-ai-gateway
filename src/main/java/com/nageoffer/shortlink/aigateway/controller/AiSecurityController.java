package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.audit.AuditLogService;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.ConsoleLoginReqDTO;
import com.nageoffer.shortlink.aigateway.security.ConsoleAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/security")
@Tag(name = "控制台安全", description = "鉴权与角色配置")
public class AiSecurityController {

    private final AiGatewayProperties properties;

    private final ConsoleAuthService consoleAuthService;

    private final AuditLogService auditLogService;

    @Operation(summary = "登录校验")
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody ConsoleLoginReqDTO requestParam) {
        ConsoleAuthService.AuthPrincipal principal;
        if (!properties.getSecurity().isEnabled()) {
            return Map.of("success", true, "token", "local-dev", "role", "admin", "securityEnabled", false);
        }
        if (requestParam == null || requestParam.getUsername() == null || requestParam.getUsername().isBlank()
                || requestParam.getPassword() == null || requestParam.getPassword().isBlank()) {
            return Map.of("success", false, "message", "请输入用户名与密码");
        }
        ConsoleAuthService.LoginResult session = consoleAuthService.login(requestParam.getUsername(), requestParam.getPassword());
        principal = new ConsoleAuthService.AuthPrincipal(session.username(), session.role());
        auditLogService.record(principal.username(), principal.role(), "LOGIN", "/v1/security/login", true, "登录成功");
        return Map.of(
                "success", true,
                "token", session.token(),
                "role", session.role(),
                "expiresAt", session.expiresAt().toString(),
                "securityEnabled", true
        );
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public Map<String, Object> logout(ServerWebExchange exchange) {
        if (!properties.getSecurity().isEnabled()) {
            return Map.of("success", true, "securityEnabled", false);
        }
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String token = headers.getFirst("X-Console-Token");
        ConsoleAuthService.AuthPrincipal principal = consoleAuthService.authenticate(headers);
        consoleAuthService.logout(token);
        auditLogService.record(principal.username(), principal.role(), "LOGOUT", "/v1/security/logout", true, "退出登录");
        return Map.of("success", true);
    }

    @Operation(summary = "查询安全配置")
    @GetMapping("/config")
    public Map<String, Object> config(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        ConsoleAuthService.AuthPrincipal principal = consoleAuthService.authenticate(headers);
        return Map.of(
                "enabled", properties.getSecurity().isEnabled(),
                "writeRoles", properties.getSecurity().getWriteRoles(),
                "currentRole", principal.role(),
                "currentUser", principal.username(),
                "sessionTtlMinutes", properties.getSecurity().getSessionTtlMinutes()
        );
    }

    @Operation(summary = "更新安全配置")
    @PostMapping("/config")
    public Map<String, Object> update(@RequestBody Map<String, Object> requestParam, ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        ConsoleAuthService.AuthPrincipal principal = consoleAuthService.authenticate(headers);
        consoleAuthService.assertWriteAllowed(principal);

        Object enabled = requestParam.get("enabled");
        if (enabled instanceof Boolean enabledValue) {
            properties.getSecurity().setEnabled(enabledValue);
        }
        Object writeRoles = requestParam.get("writeRoles");
        if (writeRoles instanceof java.util.List<?> listValue) {
            java.util.List<String> roles = listValue.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(each -> !each.isBlank())
                    .distinct()
                    .toList();
            if (!roles.isEmpty()) {
                properties.getSecurity().setWriteRoles(roles);
            }
        }

        auditLogService.record(principal.username(), principal.role(), "SECURITY_UPDATE", "/v1/security/config", true, "更新安全配置");
        return config(exchange);
    }
}
