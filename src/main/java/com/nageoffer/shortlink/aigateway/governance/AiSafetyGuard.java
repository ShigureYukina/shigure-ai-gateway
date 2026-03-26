package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
/**
 * 内容安全守卫。
 * <p>
 * - 输入阶段：命中违禁词直接拒绝。
 * - 输出阶段：按策略执行拦截或脱敏替换。
 */
public class AiSafetyGuard {

    private static final String MASK = "***";

    private final AiGatewayProperties properties;

    /**
     * 输入内容安全校验。
     */
    public void verifyInput(String content) {
        if (!properties.getSafety().isEnabled()) {
            return;
        }
        if (!StringUtils.hasText(content)) {
            return;
        }

        boolean blockedWordHit = containsAnyBlockedWord(content, properties.getSafety().getBlockedWords());
        boolean injectionHit = containsAnyKeywordIgnoreCase(content, properties.getSafety().getPromptInjectionPatterns());
        boolean piiHit = matchAnyRegex(content, properties.getSafety().getPiiPatterns());

        if (!(blockedWordHit || injectionHit || piiHit)) {
            return;
        }

        String inputStrategy = properties.getSafety().getInputStrategy();
        if ("audit".equalsIgnoreCase(inputStrategy)) {
            return;
        }

        throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, "输入内容命中安全规则（blockedWords/promptInjection/PII）");
    }

    /**
     * 输出内容安全处理。
     * <p>
     * - intercept：命中即拦截
     * - redact / replace：命中即脱敏替换
     * - audit：仅审计，不修改
     */
    public String processOutput(String content) {
        if (!properties.getSafety().isEnabled() || !StringUtils.hasText(content)) {
            return content;
        }

        boolean blockedWordHit = containsAnyBlockedWord(content, properties.getSafety().getBlockedWords());
        boolean injectionHit = containsAnyKeywordIgnoreCase(content, properties.getSafety().getPromptInjectionPatterns());
        boolean piiHit = matchAnyRegex(content, properties.getSafety().getPiiPatterns());

        if (!(blockedWordHit || injectionHit || piiHit)) {
            return content;
        }

        String outputStrategy = properties.getSafety().getOutputStrategy();
        if ("audit".equalsIgnoreCase(outputStrategy)) {
            return content;
        }

        if ("replace".equalsIgnoreCase(outputStrategy) || "redact".equalsIgnoreCase(outputStrategy)) {
            return redact(content);
        }

        throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, "输出内容命中安全规则（blockedWords/promptInjection/PII）");
    }

    private String redact(String content) {
        String result = content;
        String mask = StringUtils.hasText(properties.getSafety().getRedactMask())
                ? properties.getSafety().getRedactMask()
                : MASK;

        Set<String> blockedWords = properties.getSafety().getBlockedWords();
        if (blockedWords == null || blockedWords.isEmpty()) {
            return redactByRegex(result, mask);
        }
        for (String each : blockedWords) {
            if (StringUtils.hasText(each)) {
                result = replaceIgnoreCase(result, each, mask);
            }
        }
        return redactByRegex(result, mask);
    }

    private String redactByRegex(String content, String mask) {
        String result = content;
        List<String> piiPatterns = properties.getSafety().getPiiPatterns();
        if (piiPatterns == null) {
            return result;
        }
        for (String regex : piiPatterns) {
            if (!StringUtils.hasText(regex)) {
                continue;
            }
            try {
                result = Pattern.compile(regex).matcher(result).replaceAll(mask);
            } catch (Exception ignored) {
                // ignore invalid regex in runtime config
            }
        }
        return result;
    }

    private boolean containsAnyBlockedWord(String content, Set<String> blockedWords) {
        if (blockedWords == null || blockedWords.isEmpty()) {
            return false;
        }
        for (String each : blockedWords) {
            if (!StringUtils.hasText(each)) {
                continue;
            }
            if (content.contains(each)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAnyKeywordIgnoreCase(String content, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        String lowered = content.toLowerCase();
        for (String each : keywords) {
            if (StringUtils.hasText(each) && lowered.contains(each.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchAnyRegex(String content, List<String> regexList) {
        if (regexList == null || regexList.isEmpty()) {
            return false;
        }
        for (String regex : regexList) {
            if (!StringUtils.hasText(regex)) {
                continue;
            }
            try {
                if (Pattern.compile(regex).matcher(content).find()) {
                    return true;
                }
            } catch (Exception ignored) {
                // ignore invalid regex in runtime config
            }
        }
        return false;
    }

    private String replaceIgnoreCase(String source, String target, String replacement) {
        if (!StringUtils.hasText(source) || !StringUtils.hasText(target)) {
            return source;
        }
        return Pattern.compile(Pattern.quote(target), Pattern.CASE_INSENSITIVE)
                .matcher(source)
                .replaceAll(replacement);
    }
}
