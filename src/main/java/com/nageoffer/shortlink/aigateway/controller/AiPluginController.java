package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/plugins")
@Tag(name = "插件管理", description = "轻量插件链运行时配置")
public class AiPluginController {

    private final AiGatewayProperties properties;

    @Operation(summary = "切换插件启停", description = "按插件名在运行时启用/禁用")
    @PostMapping("/toggle")
    public Map<String, Object> toggle(@RequestParam("name") String name,
                                      @RequestParam("enabled") boolean enabled) {
        properties.getPlugin().getPluginEnabledMap().put(name, enabled);
        return Map.of(
                "name", name,
                "enabled", enabled,
                "pluginEnabledMap", properties.getPlugin().getPluginEnabledMap()
        );
    }

    @Operation(summary = "获取插件配置", description = "返回全局插件、路由插件与启停状态")
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "enabled", properties.getPlugin().isEnabled(),
                "globalPlugins", properties.getPlugin().getGlobalPlugins(),
                "routePlugins", properties.getPlugin().getRoutePlugins(),
                "pluginEnabledMap", properties.getPlugin().getPluginEnabledMap()
        );
    }
}
