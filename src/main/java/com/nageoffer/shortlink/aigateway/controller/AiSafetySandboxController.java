package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/safety")
@Tag(name = "安全策略", description = "输入/输出安全过滤配置")
public class AiSafetySandboxController {

    private final AiGatewayProperties properties;

    @Operation(summary = "安全规则沙箱检测", description = "输入任意文本，返回命中的 blockedWords/promptInjection/pii 规则")
    @PostMapping("/sandbox/check")
    public Map<String, Object> check(@RequestBody Map<String, Object> requestParam) {
        String content = String.valueOf(requestParam.getOrDefault("content", ""));
        String lowered = content.toLowerCase();

        List<String> blockedHits = new ArrayList<>();
        if (properties.getSafety().getBlockedWords() != null) {
            for (String each : properties.getSafety().getBlockedWords()) {
                if (StringUtils.hasText(each) && content.contains(each)) {
                    blockedHits.add(each);
                }
            }
        }

        List<String> injectionHits = new ArrayList<>();
        if (properties.getSafety().getPromptInjectionPatterns() != null) {
            for (String each : properties.getSafety().getPromptInjectionPatterns()) {
                if (StringUtils.hasText(each) && lowered.contains(each.toLowerCase())) {
                    injectionHits.add(each);
                }
            }
        }

        List<String> piiHits = new ArrayList<>();
        if (properties.getSafety().getPiiPatterns() != null) {
            for (String each : properties.getSafety().getPiiPatterns()) {
                if (!StringUtils.hasText(each)) {
                    continue;
                }
                try {
                    if (Pattern.compile(each).matcher(content).find()) {
                        piiHits.add(each);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return Map.of(
                "matched", !blockedHits.isEmpty() || !injectionHits.isEmpty() || !piiHits.isEmpty(),
                "blockedWordHits", blockedHits,
                "promptInjectionHits", injectionHits,
                "piiHits", piiHits,
                "inputStrategy", properties.getSafety().getInputStrategy(),
                "outputStrategy", properties.getSafety().getOutputStrategy()
        );
    }
}
