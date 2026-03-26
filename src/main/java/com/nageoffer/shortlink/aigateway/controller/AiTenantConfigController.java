package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.audit.AuditLogService;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigManagementService;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import com.nageoffer.shortlink.aigateway.security.ConsoleAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/v1/tenant-config")
@Tag(name = "租户配置", description = "DB-backed tenant config 管理")
public class AiTenantConfigController {

    private final ConsoleAuthService consoleAuthService;
    private final AuditLogService auditLogService;
    private final TenantConfigQueryService tenantConfigQueryService;
    private final TenantConfigManagementService tenantConfigManagementService;

    public AiTenantConfigController(ConsoleAuthService consoleAuthService,
                                    AuditLogService auditLogService,
                                    TenantConfigQueryService tenantConfigQueryService,
                                    TenantConfigManagementService tenantConfigManagementService) {
        this.consoleAuthService = consoleAuthService;
        this.auditLogService = auditLogService;
        this.tenantConfigQueryService = tenantConfigQueryService;
        this.tenantConfigManagementService = tenantConfigManagementService;
    }

    @Operation(summary = "查询 API Key 配置")
    @GetMapping("/api-keys/{apiKey}")
    public Map<String, Object> apiKey(@PathVariable String apiKey, ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), false);
        return tenantConfigQueryService.findApiKeyCredential(apiKey)
                .map(each -> Map.<String, Object>of(
                        "tenantId", each.getTenantId(),
                        "appId", each.getAppId(),
                        "keyId", each.getKeyId(),
                        "enabled", each.isEnabled(),
                        "expiresAt", each.getExpiresAt() == null ? "" : each.getExpiresAt().toString(),
                        "currentUser", principal.username()
                ))
                .orElse(Map.of("found", false, "currentUser", principal.username()));
    }

    @Operation(summary = "查询租户")
    @GetMapping("/tenants/{tenantId}")
    public Mono<Map<String, Object>> tenant(@PathVariable String tenantId, ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), false);
        return tenantConfigManagementService.getTenant(tenantId)
                .map(result -> appendCurrentUser(result, principal.username()));
    }

    @Operation(summary = "查询租户应用")
    @GetMapping("/tenants/{tenantId}/apps/{appId}")
    public Mono<Map<String, Object>> tenantApp(@PathVariable String tenantId,
                                               @PathVariable String appId,
                                               ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), false);
        return tenantConfigManagementService.getTenantApp(tenantId, appId)
                .map(result -> appendCurrentUser(result, principal.username()));
    }

    @Operation(summary = "列出租户应用")
    @GetMapping("/tenants/{tenantId}/apps")
    public Mono<Map<String, Object>> tenantApps(@PathVariable String tenantId, ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), false);
        return tenantConfigManagementService.listTenantApps(tenantId)
                .collectList()
                .map(items -> Map.of(
                        "tenantId", tenantId,
                        "items", items,
                        "count", items.size(),
                        "currentUser", principal.username()
                ));
    }

    @Operation(summary = "新增或更新租户")
    @PostMapping("/tenants")
    public Mono<Map<String, Object>> upsertTenant(@RequestBody Map<String, Object> request, ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), true);
        return tenantConfigManagementService.upsertTenant(request)
                .doOnSuccess(result -> auditLogService.record(principal.username(), principal.role(), "TENANT_UPSERT", "/v1/tenant-config/tenants", true, String.valueOf(result)));
    }

    @Operation(summary = "新增或更新租户应用")
    @PostMapping("/tenants/{tenantId}/apps")
    public Mono<Map<String, Object>> upsertTenantApp(@PathVariable String tenantId,
                                                     @RequestBody Map<String, Object> request,
                                                     ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), true);
        return tenantConfigManagementService.upsertTenantApp(tenantId, request)
                .doOnSuccess(result -> auditLogService.record(principal.username(), principal.role(), "TENANT_APP_UPSERT", "/v1/tenant-config/tenants/" + tenantId + "/apps", true, String.valueOf(result)));
    }

    @Operation(summary = "删除租户")
    @DeleteMapping("/tenants/{tenantId}")
    public Mono<Map<String, Object>> deleteTenant(@PathVariable String tenantId, ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), true);
        return tenantConfigManagementService.deleteTenant(tenantId)
                .doOnSuccess(result -> auditLogService.record(principal.username(), principal.role(), "TENANT_DELETE", "/v1/tenant-config/tenants/" + tenantId, true, String.valueOf(result)));
    }

    @Operation(summary = "删除租户应用")
    @DeleteMapping("/tenants/{tenantId}/apps/{appId}")
    public Mono<Map<String, Object>> deleteTenantApp(@PathVariable String tenantId,
                                                     @PathVariable String appId,
                                                     ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), true);
        return tenantConfigManagementService.deleteTenantApp(tenantId, appId)
                .doOnSuccess(result -> auditLogService.record(principal.username(), principal.role(), "TENANT_APP_DELETE", "/v1/tenant-config/tenants/" + tenantId + "/apps/" + appId, true, String.valueOf(result)));
    }

    @Operation(summary = "新增或更新 API Key 配置")
    @PostMapping("/api-keys")
    public Mono<Map<String, Object>> upsertApiKey(@RequestBody Map<String, Object> request, ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), true);
        return tenantConfigManagementService.upsertApiKey(request)
                .doOnSuccess(result -> auditLogService.record(principal.username(), principal.role(), "TENANT_API_KEY_UPSERT", "/v1/tenant-config/api-keys", true, String.valueOf(result)));
    }

    @Operation(summary = "删除 API Key 配置")
    @DeleteMapping("/api-keys/{apiKey}")
    public Mono<Map<String, Object>> deleteApiKey(@PathVariable String apiKey, ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), true);
        return tenantConfigManagementService.deleteApiKey(apiKey)
                .doOnSuccess(result -> auditLogService.record(principal.username(), principal.role(), "TENANT_API_KEY_DELETE", "/v1/tenant-config/api-keys/" + apiKey, true, String.valueOf(result)));
    }

    @Operation(summary = "查询租户模型策略")
    @GetMapping("/model-policies/{tenantId}")
    public Map<String, Object> modelPolicy(@PathVariable String tenantId, ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), false);
        return tenantConfigQueryService.findModelPolicy(tenantId)
                .map(each -> Map.<String, Object>of(
                        "tenantId", tenantId,
                        "enabled", each.isEnabled(),
                        "allowedModels", each.getAllowedModels(),
                        "modelMappings", each.getModelMappings(),
                        "defaultModelAlias", each.getDefaultModelAlias(),
                        "defaultModel", each.getDefaultModel() == null ? "" : each.getDefaultModel(),
                        "currentUser", principal.username()
                ))
                .orElse(Map.of("found", false, "tenantId", tenantId, "currentUser", principal.username()));
    }

    @Operation(summary = "新增或更新租户模型策略")
    @PostMapping("/model-policies/{tenantId}")
    public Mono<Map<String, Object>> upsertModelPolicy(@PathVariable String tenantId,
                                                       @RequestBody Map<String, Object> request,
                                                       ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), true);
        return tenantConfigManagementService.upsertModelPolicy(tenantId, request)
                .doOnSuccess(result -> auditLogService.record(principal.username(), principal.role(), "TENANT_MODEL_POLICY_UPSERT", "/v1/tenant-config/model-policies/" + tenantId, true, String.valueOf(result)));
    }

    @Operation(summary = "删除租户模型策略")
    @DeleteMapping("/model-policies/{tenantId}")
    public Mono<Map<String, Object>> deleteModelPolicy(@PathVariable String tenantId, ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), true);
        return tenantConfigManagementService.deleteModelPolicy(tenantId)
                .doOnSuccess(result -> auditLogService.record(principal.username(), principal.role(), "TENANT_MODEL_POLICY_DELETE", "/v1/tenant-config/model-policies/" + tenantId, true, String.valueOf(result)));
    }

    @Operation(summary = "查询租户配额策略")
    @GetMapping("/quota-policies/{tenantId}")
    public Map<String, Object> quotaPolicy(@PathVariable String tenantId, ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), false);
        return tenantConfigQueryService.findQuotaPolicy(tenantId)
                .map(each -> Map.<String, Object>of(
                        "tenantId", tenantId,
                        "enabled", each.isEnabled(),
                        "tokenQuotaPerMinute", each.getTokenQuotaPerMinute() == null ? 0L : each.getTokenQuotaPerMinute(),
                        "tokenQuotaPerDay", each.getTokenQuotaPerDay() == null ? 0L : each.getTokenQuotaPerDay(),
                        "tokenQuotaPerMonth", each.getTokenQuotaPerMonth() == null ? 0L : each.getTokenQuotaPerMonth(),
                        "currentUser", principal.username()
                ))
                .orElse(Map.of("found", false, "tenantId", tenantId, "currentUser", principal.username()));
    }

    @Operation(summary = "新增或更新租户配额策略")
    @PostMapping("/quota-policies/{tenantId}")
    public Mono<Map<String, Object>> upsertQuotaPolicy(@PathVariable String tenantId,
                                                       @RequestBody Map<String, Object> request,
                                                       ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), true);
        return tenantConfigManagementService.upsertQuotaPolicy(tenantId, request)
                .doOnSuccess(result -> auditLogService.record(principal.username(), principal.role(), "TENANT_QUOTA_POLICY_UPSERT", "/v1/tenant-config/quota-policies/" + tenantId, true, String.valueOf(result)));
    }

    @Operation(summary = "删除租户配额策略")
    @DeleteMapping("/quota-policies/{tenantId}")
    public Mono<Map<String, Object>> deleteQuotaPolicy(@PathVariable String tenantId, ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), true);
        return tenantConfigManagementService.deleteQuotaPolicy(tenantId)
                .doOnSuccess(result -> auditLogService.record(principal.username(), principal.role(), "TENANT_QUOTA_POLICY_DELETE", "/v1/tenant-config/quota-policies/" + tenantId, true, String.valueOf(result)));
    }

    @Operation(summary = "查询模型价格")
    @GetMapping("/model-prices/{model}")
    public Map<String, Object> modelPrice(@PathVariable String model, ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), false);
        return tenantConfigQueryService.findModelPrice(model)
                .map(each -> Map.<String, Object>of(
                        "model", model,
                        "inputPer1k", each.getInputPer1k() == null ? 0D : each.getInputPer1k(),
                        "outputPer1k", each.getOutputPer1k() == null ? 0D : each.getOutputPer1k(),
                        "currentUser", principal.username()
                ))
                .orElse(Map.of("found", false, "model", model, "currentUser", principal.username()));
    }

    @Operation(summary = "新增或更新模型价格")
    @PostMapping("/model-prices/{model}")
    public Mono<Map<String, Object>> upsertModelPrice(@PathVariable String model,
                                                      @RequestBody Map<String, Object> request,
                                                      ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), true);
        return tenantConfigManagementService.upsertModelPrice(model, request)
                .doOnSuccess(result -> auditLogService.record(principal.username(), principal.role(), "MODEL_PRICE_UPSERT", "/v1/tenant-config/model-prices/" + model, true, String.valueOf(result)));
    }

    @Operation(summary = "删除模型价格")
    @DeleteMapping("/model-prices/{model}")
    public Mono<Map<String, Object>> deleteModelPrice(@PathVariable String model, ServerWebExchange exchange) {
        ConsoleAuthService.AuthPrincipal principal = authenticateWrite(exchange.getRequest().getHeaders(), true);
        return tenantConfigManagementService.deleteModelPrice(model)
                .doOnSuccess(result -> auditLogService.record(principal.username(), principal.role(), "MODEL_PRICE_DELETE", "/v1/tenant-config/model-prices/" + model, true, String.valueOf(result)));
    }

    private ConsoleAuthService.AuthPrincipal authenticateWrite(HttpHeaders headers, boolean write) {
        ConsoleAuthService.AuthPrincipal principal = consoleAuthService.authenticate(headers);
        if (write) {
            consoleAuthService.assertWriteAllowed(principal);
        }
        return principal;
    }

    private Map<String, Object> appendCurrentUser(Map<String, Object> result, String username) {
        java.util.LinkedHashMap<String, Object> response = new java.util.LinkedHashMap<>(result);
        response.put("currentUser", username);
        return response;
    }
}
