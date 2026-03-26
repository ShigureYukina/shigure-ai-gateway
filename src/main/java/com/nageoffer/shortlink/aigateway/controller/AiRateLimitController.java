package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.governance.RedisTokenQuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/rate-limit")
@Tag(name = "限流配额", description = "Token 级限流与配额运行时管理")
public class AiRateLimitController {

    private final AiGatewayProperties properties;

    private final RedisTokenQuotaService redisTokenQuotaService;

    public AiRateLimitController(AiGatewayProperties properties, RedisTokenQuotaService redisTokenQuotaService) {
        this.properties = properties;
        this.redisTokenQuotaService = redisTokenQuotaService;
    }

    @Operation(summary = "查询限流配置", description = "返回 enabled/quota/keyDimensions 等运行时配置")
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "enabled", properties.getRateLimit().isEnabled(),
                "defaultTokenQuotaPerMinute", properties.getRateLimit().getDefaultTokenQuotaPerMinute(),
                "defaultTokenQuotaPerDay", properties.getRateLimit().getDefaultTokenQuotaPerDay(),
                "minTokenReserve", properties.getRateLimit().getMinTokenReserve(),
                "keyDimensions", properties.getRateLimit().getKeyDimensions()
        );
    }

    @Operation(summary = "更新限流配置", description = "运行时更新 enabled/default quotas/min reserve/keyDimensions")
    @PostMapping("/config")
    public Map<String, Object> update(@RequestBody Map<String, Object> requestParam) {
        Object enabled = requestParam.get("enabled");
        if (enabled instanceof Boolean enabledValue) {
            properties.getRateLimit().setEnabled(enabledValue);
        }

        Object minuteQuota = requestParam.get("defaultTokenQuotaPerMinute");
        Long minuteQuotaValue = parsePositiveLong(minuteQuota);
        if (minuteQuotaValue != null) {
            properties.getRateLimit().setDefaultTokenQuotaPerMinute(minuteQuotaValue);
        }

        Object dayQuota = requestParam.get("defaultTokenQuotaPerDay");
        Long dayQuotaValue = parsePositiveLong(dayQuota);
        if (dayQuotaValue != null) {
            properties.getRateLimit().setDefaultTokenQuotaPerDay(dayQuotaValue);
        }

        Object minTokenReserve = requestParam.get("minTokenReserve");
        Long minTokenReserveValue = parsePositiveLong(minTokenReserve);
        if (minTokenReserveValue != null) {
            properties.getRateLimit().setMinTokenReserve(minTokenReserveValue);
        }

        Object keyDimensions = requestParam.get("keyDimensions");
        if (keyDimensions instanceof java.util.List<?> listValue) {
            java.util.List<String> dims = listValue.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(each -> !each.isBlank())
                    .distinct()
                    .toList();
            if (!dims.isEmpty()) {
                properties.getRateLimit().setKeyDimensions(dims);
            }
        }

        return config();
    }

    @Operation(summary = "查询当前配额用量", description = "按当前请求头维度计算 quotaKey 并返回 minute/day 用量")
    @GetMapping("/usage")
    public Map<String, Object> usage(@RequestParam("provider") String provider,
                                     @RequestParam("model") String model,
                                     ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        Map<String, Object> usage = redisTokenQuotaService.currentUsage(headers, provider, model);
        Map<String, Object> response = new LinkedHashMap<>(usage);
        Map<String, Object> headerPreview = new LinkedHashMap<>();
        headerPreview.put("userId", headers.getFirst("userId"));
        headerPreview.put("xForwardedFor", headers.getFirst("X-Forwarded-For"));
        headerPreview.put("xRealIp", headers.getFirst("X-Real-IP"));
        headerPreview.put("xConsumer", headers.getFirst("X-Consumer"));
        response.put("headerPreview", headerPreview);
        return response;
    }

    private Long parsePositiveLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
