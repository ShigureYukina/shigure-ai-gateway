package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/safety")
@Tag(name = "安全策略", description = "输入/输出安全过滤配置")
public class AiSafetyController {

    private final AiGatewayProperties properties;

    @Operation(summary = "更新安全配置", description = "运行时更新 enabled/outputStrategy/blockedWords")
    @PostMapping("/config")
    public Map<String, Object> update(@RequestBody Map<String, Object> requestParam) {
        Object enabled = requestParam.get("enabled");
        if (enabled instanceof Boolean enabledValue) {
            properties.getSafety().setEnabled(enabledValue);
        }
        Object inputStrategy = requestParam.get("inputStrategy");
        if (inputStrategy instanceof String inputStrategyValue) {
            properties.getSafety().setInputStrategy(inputStrategyValue);
        }
        Object outputStrategy = requestParam.get("outputStrategy");
        if (outputStrategy instanceof String outputStrategyValue) {
            properties.getSafety().setOutputStrategy(outputStrategyValue);
        }
        Object blockedWords = requestParam.get("blockedWords");
        if (blockedWords instanceof java.util.List<?> listValue) {
            Set<String> words = listValue.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
            properties.getSafety().setBlockedWords(words);
        }
        Object promptInjectionPatterns = requestParam.get("promptInjectionPatterns");
        if (promptInjectionPatterns instanceof java.util.List<?> listValue) {
            java.util.List<String> patterns = listValue.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(each -> !each.isBlank())
                    .distinct()
                    .toList();
            properties.getSafety().setPromptInjectionPatterns(patterns);
        }
        Object piiPatterns = requestParam.get("piiPatterns");
        if (piiPatterns instanceof java.util.List<?> listValue) {
            java.util.List<String> patterns = listValue.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(each -> !each.isBlank())
                    .distinct()
                    .toList();
            properties.getSafety().setPiiPatterns(patterns);
        }
        Object redactMask = requestParam.get("redactMask");
        if (redactMask instanceof String redactMaskValue && !redactMaskValue.isBlank()) {
            properties.getSafety().setRedactMask(redactMaskValue);
        }
        return current();
    }

    @Operation(summary = "查询安全配置", description = "获取当前安全策略配置")
    @GetMapping("/config")
    public Map<String, Object> current() {
        return Map.of(
                "enabled", properties.getSafety().isEnabled(),
                "inputStrategy", properties.getSafety().getInputStrategy(),
                "outputStrategy", properties.getSafety().getOutputStrategy(),
                "blockedWords", properties.getSafety().getBlockedWords(),
                "promptInjectionPatterns", properties.getSafety().getPromptInjectionPatterns(),
                "piiPatterns", properties.getSafety().getPiiPatterns(),
                "redactMask", properties.getSafety().getRedactMask()
        );
    }
}
