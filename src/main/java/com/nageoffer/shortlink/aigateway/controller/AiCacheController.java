package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.governance.AiCacheStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/cache")
@Tag(name = "缓存治理", description = "缓存配置与命中统计")
public class AiCacheController {

    private final AiGatewayProperties properties;

    private final AiCacheStatsService aiCacheStatsService;

    @Operation(summary = "查询缓存配置")
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "enabled", properties.getCache().isEnabled(),
                "ttlSeconds", properties.getCache().getTtl().toSeconds(),
                "semanticCacheEnabled", properties.getCache().isSemanticCacheEnabled()
        );
    }

    @Operation(summary = "更新缓存配置")
    @PostMapping("/config")
    public Map<String, Object> update(@RequestBody Map<String, Object> requestParam) {
        Object enabled = requestParam.get("enabled");
        if (enabled instanceof Boolean enabledValue) {
            properties.getCache().setEnabled(enabledValue);
        }
        Object semanticEnabled = requestParam.get("semanticCacheEnabled");
        if (semanticEnabled instanceof Boolean semanticEnabledValue) {
            properties.getCache().setSemanticCacheEnabled(semanticEnabledValue);
        }
        Object ttlSeconds = requestParam.get("ttlSeconds");
        if (ttlSeconds != null) {
            try {
                long parsed = Long.parseLong(String.valueOf(ttlSeconds));
                if (parsed > 0) {
                    properties.getCache().setTtl(Duration.ofSeconds(parsed));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return config();
    }

    @Operation(summary = "查询缓存统计")
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return aiCacheStatsService.snapshot();
    }

    @Operation(summary = "重置缓存统计")
    @PostMapping("/stats/reset")
    public Map<String, Object> resetStats() {
        return aiCacheStatsService.reset();
    }

    @Operation(summary = "查询缓存趋势", description = "返回最近 N 分钟缓存 hit/miss/命中率趋势")
    @GetMapping("/stats/trend")
    public Map<String, Object> trend(@RequestParam(value = "minutes", defaultValue = "60") int minutes) {
        return Map.of(
                "minutes", minutes,
                "items", aiCacheStatsService.trend(minutes)
        );
    }
}
