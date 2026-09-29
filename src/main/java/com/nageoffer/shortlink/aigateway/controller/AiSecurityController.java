package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.audit.AuditLogService;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.ConsoleLoginReqDTO;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigDomain;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigPublisher;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigSupport;
import com.nageoffer.shortlink.aigateway.security.ConsoleAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/v1/security")
@Tag(name = "控制台安全", description = "鉴权与角色配置")
public class AiSecurityController {

    private final AiGatewayProperties properties;

    private final ConsoleAuthService consoleAuthService;

    private final AuditLogService auditLogService;

    private final RuntimeConfigPublisher runtimeConfigPublisher;

    @Autowired
    public AiSecurityController(AiGatewayProperties properties,
                                ConsoleAuthService consoleAuthService,
                                AuditLogService auditLogService,
                                RuntimeConfigPublisher runtimeConfigPublisher) {
        this.properties = properties;
        this.consoleAuthService = consoleAuthService;
        this.auditLogService = auditLogService;
        this.runtimeConfigPublisher = runtimeConfigPublisher;
    }

    /**
     * 保留给不接配置中心的单元测试：写入只改本实例内存。
     */
    public AiSecurityController(AiGatewayProperties properties,
                                ConsoleAuthService consoleAuthService,
                                AuditLogService auditLogService) {
        this(properties, consoleAuthService, auditLogService, RuntimeConfigPublisher.noPersistence());
    }

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
        return config(consoleAuthService.authenticate(exchange.getRequest().getHeaders()));
    }

    @Operation(summary = "更新安全配置",
            description = "运行时更新 enabled/writeRoles；响应里的 persisted=false 表示仅本实例生效")
    @PostMapping("/config")
    public Mono<Map<String, Object>> update(@RequestBody Map<String, Object> requestParam, ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        ConsoleAuthService.AuthPrincipal principal = consoleAuthService.authenticate(headers);
        consoleAuthService.assertWriteAllowed(principal);

        Object enabled = requestParam.get("enabled");
        if (enabled instanceof Boolean enabledValue) {
            if (!enabledValue && RuntimeConfigSupport.securityForceEnabled()) {
                throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST,
                        "本实例的安全开关被 " + RuntimeConfigSupport.SECURITY_FORCE_ENABLED_KEY
                                + " 强制开启，拒绝关闭；请先移除该环境变量或系统属性");
            }
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
        return runtimeConfigPublisher.save(RuntimeConfigDomain.SECURITY, config(principal));
    }

    private Map<String, Object> config(ConsoleAuthService.AuthPrincipal principal) {
        return Map.of(
                "enabled", properties.getSecurity().isEnabled(),
                "writeRoles", properties.getSecurity().getWriteRoles(),
                "currentRole", principal.role(),
                "currentUser", principal.username(),
                "sessionTtlMinutes", properties.getSecurity().getSessionTtlMinutes()
        );
    }
}
