package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.util.DigestUtils;
import java.util.LinkedHashMap;

@Service
@RequiredArgsConstructor
/**
 * Provider 路由解析服务。
 * <p>
 * 根据请求头与模型别名决定目标 provider、目标模型与上游地址，
 * 同时合并 provider 级与全局超时/重试策略。
 */
public class ProviderRoutingService {

    private final AiGatewayProperties properties;

    /**
     * 解析本次请求的完整路由结果。
     */
    public AiRoutingResult resolve(String clientModel, HttpHeaders headers) {
        String selectedProvider = resolveProvider(headers, clientModel);
        ResolvedModel primaryResolvedModel = resolveModel(selectedProvider, clientModel);
        String provider = primaryResolvedModel.provider();
        String providerModel = primaryResolvedModel.providerModel();
        String upstreamUri = buildChatUri(provider);
        boolean abHit = isAbHit(headers, clientModel);
        String routeSource = abHit ? "ab" : (StringUtils.hasText(headers.getFirst("X-AI-Provider")) ? "header" : "default");

        List<AiRoutingResult.FallbackRouteTarget> fallbackCandidates = resolveFallbackCandidates(provider, providerModel);
        return AiRoutingResult.builder()
                .provider(provider)
                .providerModel(providerModel)
                .upstreamUri(upstreamUri)
                .routePolicy(resolveRoutePolicy(provider))
                .routeSource(routeSource)
                .abHit(abHit)
                .fallbackCandidates(fallbackCandidates)
                .build();
    }

    public Map<String, Object> routingConfig() {
        return Map.of(
                "defaultProvider", properties.getUpstream().getDefaultProvider(),
                "providerBaseUrl", properties.getUpstream().getProviderBaseUrl(),
                "modelAlias", properties.getUpstream().getModelAlias(),
                "fallbackEnabled", properties.getRouting().isFallbackEnabled(),
                "providerPriority", properties.getRouting().getProviderPriority(),
                "abEnabled", properties.getRouting().isAbEnabled(),
                "abProvider", properties.getRouting().getAbProvider(),
                "abPercentage", properties.getRouting().getAbPercentage()
        );
    }

    public Map<String, Object> updateRoutingConfig(Map<String, Object> requestParam) {
        Object defaultProvider = requestParam.get("defaultProvider");
        if (defaultProvider instanceof String defaultProviderValue && StringUtils.hasText(defaultProviderValue)) {
            properties.getUpstream().setDefaultProvider(defaultProviderValue.trim());
        }

        Object fallbackEnabled = requestParam.get("fallbackEnabled");
        if (fallbackEnabled instanceof Boolean fallbackEnabledValue) {
            properties.getRouting().setFallbackEnabled(fallbackEnabledValue);
        }

        Object providerPriority = requestParam.get("providerPriority");
        if (providerPriority instanceof List<?> listValue) {
            List<String> normalized = listValue.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            if (!normalized.isEmpty()) {
                properties.getRouting().setProviderPriority(normalized);
            }
        }

        Object abEnabled = requestParam.get("abEnabled");
        if (abEnabled instanceof Boolean abEnabledValue) {
            properties.getRouting().setAbEnabled(abEnabledValue);
        }
        Object abProvider = requestParam.get("abProvider");
        if (abProvider instanceof String abProviderValue && StringUtils.hasText(abProviderValue)) {
            properties.getRouting().setAbProvider(abProviderValue.trim());
        }
        Object abPercentage = requestParam.get("abPercentage");
        if (abPercentage != null) {
            try {
                int parsed = Integer.parseInt(String.valueOf(abPercentage));
                if (parsed < 0) {
                    parsed = 0;
                }
                if (parsed > 100) {
                    parsed = 100;
                }
                properties.getRouting().setAbPercentage(parsed);
            } catch (NumberFormatException ignored) {
            }
        }

        Object providerBaseUrl = requestParam.get("providerBaseUrl");
        if (providerBaseUrl instanceof Map<?, ?> mapValue) {
            Map<String, String> normalizedMap = new java.util.LinkedHashMap<>();
            mapValue.forEach((k, v) -> {
                String key = String.valueOf(k).trim();
                String value = v == null ? "" : String.valueOf(v).trim();
                if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
                    normalizedMap.put(key, value);
                }
            });
            if (!normalizedMap.isEmpty()) {
                properties.getUpstream().setProviderBaseUrl(normalizedMap);
            }
        }

        Object modelAlias = requestParam.get("modelAlias");
        if (modelAlias instanceof Map<?, ?> mapValue) {
            Map<String, String> normalizedMap = new java.util.LinkedHashMap<>();
            mapValue.forEach((k, v) -> {
                String key = String.valueOf(k).trim();
                String value = v == null ? "" : String.valueOf(v).trim();
                if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
                    normalizedMap.put(key, value);
                }
            });
            properties.getUpstream().setModelAlias(normalizedMap);
        }

        return routingConfig();
    }

    public Map<String, Object> preview(String model, HttpHeaders headers) {
        AiRoutingResult routing = resolve(model, headers);
        int bucket = resolveBucket(headers, model);
        return Map.of(
                "provider", routing.getProvider(),
                "providerModel", routing.getProviderModel(),
                "upstreamUri", routing.getUpstreamUri(),
                "routeSource", routing.getRouteSource(),
                "abHit", routing.getAbHit(),
                "abBucket", bucket,
                "abThreshold", properties.getRouting().getAbPercentage(),
                "routePolicy", routing.getRoutePolicy(),
                "fallbackCandidates", routing.getFallbackCandidates() == null ? List.of() : routing.getFallbackCandidates()
        );
    }

    public Map<String, Object> simulateAb(String model, int samples) {
        int normalized = Math.min(Math.max(samples, 1), 2000);
        int bHit = 0;
        Map<String, Integer> providerCounter = new LinkedHashMap<>();
        List<Map<String, Object>> firstSamples = new ArrayList<>();
        for (int i = 1; i <= normalized; i++) {
            HttpHeaders headers = new HttpHeaders();
            headers.set("userId", "sim-user-" + i);
            int bucket = resolveBucket(headers, model);
            boolean hit = isAbHit(headers, model);
            if (hit) {
                bHit++;
            }
            AiRoutingResult routing = resolve(model, headers);
            providerCounter.merge(routing.getProvider(), 1, Integer::sum);
            if (i <= 50) {
                firstSamples.add(Map.of(
                        "userId", "sim-user-" + i,
                        "bucket", bucket,
                        "provider", routing.getProvider(),
                        "abHit", hit,
                        "routeSource", routing.getRouteSource()
                ));
            }
        }
        return Map.of(
                "model", model,
                "samples", normalized,
                "abEnabled", properties.getRouting().isAbEnabled(),
                "abProvider", properties.getRouting().getAbProvider(),
                "abPercentage", properties.getRouting().getAbPercentage(),
                "abHitCount", bHit,
                "abHitRate", normalized == 0 ? 0D : (double) bHit / normalized,
                "providerDistribution", providerCounter,
                "samplePreview", firstSamples
        );
    }

    /**
     * 优先读取请求头 X-AI-Provider，未指定时使用默认 provider。
     */
    private String resolveProvider(HttpHeaders headers, String clientModel) {
        if (isAbHit(headers, clientModel)) {
            String abProvider = properties.getRouting().getAbProvider();
            if (StringUtils.hasText(abProvider) && StringUtils.hasText(properties.getUpstream().getProviderBaseUrl().get(abProvider))) {
                return abProvider;
            }
        }
        String provider = headers.getFirst("X-AI-Provider");
        return StringUtils.hasText(provider) ? provider : properties.getUpstream().getDefaultProvider();
    }

    private boolean isAbHit(HttpHeaders headers, String clientModel) {
        if (!properties.getRouting().isAbEnabled()) {
            return false;
        }
        Integer percentage = properties.getRouting().getAbPercentage();
        if (percentage == null || percentage <= 0) {
            return false;
        }
        int bucket = resolveBucket(headers, clientModel);
        return bucket < percentage;
    }

    private int resolveBucket(HttpHeaders headers, String clientModel) {
        String hashSeed = headers.getFirst("userId");
        if (!StringUtils.hasText(hashSeed)) {
            hashSeed = headers.getFirst("X-Request-Id");
        }
        if (!StringUtils.hasText(hashSeed)) {
            hashSeed = clientModel;
        }
        String hash = DigestUtils.md5DigestAsHex(hashSeed.getBytes(StandardCharsets.UTF_8));
        return Integer.parseInt(hash.substring(0, 4), 16) % 100;
    }

    /**
     * 处理模型别名映射。
     * <p>
     * 支持两种格式：
     * 1) provider:model（可在别名中覆盖 provider）
     * 2) model（仅替换模型名）
     */
    private ResolvedModel resolveModel(String provider, String clientModel) {
        Map<String, String> modelAlias = properties.getUpstream().getModelAlias();
        String aliasValue = modelAlias.get(clientModel);
        if (!StringUtils.hasText(aliasValue)) {
            return new ResolvedModel(provider, clientModel);
        }
        String[] parts = aliasValue.split(":", 2);
        if (parts.length == 2) {
            return new ResolvedModel(parts[0], parts[1]);
        }
        return new ResolvedModel(provider, aliasValue);
    }

    /**
     * 归一化 baseUrl，去除尾部斜杠避免路径拼接重复。
     */
    private String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String buildChatUri(String provider) {
        String baseUrl = properties.getUpstream().getProviderBaseUrl().get(provider);
        if (!StringUtils.hasText(baseUrl)) {
            throw new AiGatewayClientException(AiGatewayErrorCode.PROVIDER_NOT_CONFIGURED, "未配置Provider地址: " + provider);
        }
        return normalizeBaseUrl(baseUrl) + "/v1/chat/completions";
    }

    private List<AiRoutingResult.FallbackRouteTarget> resolveFallbackCandidates(String primaryProvider, String primaryProviderModel) {
        if (!properties.getRouting().isFallbackEnabled()) {
            return List.of();
        }
        Set<String> orderedProviders = new LinkedHashSet<>();
        List<String> priority = properties.getRouting().getProviderPriority();
        if (priority != null && !priority.isEmpty()) {
            orderedProviders.addAll(priority);
        }
        orderedProviders.addAll(properties.getUpstream().getProviderBaseUrl().keySet());
        orderedProviders.remove(primaryProvider);

        List<AiRoutingResult.FallbackRouteTarget> result = new ArrayList<>();
        for (String candidateProvider : orderedProviders) {
            if (!StringUtils.hasText(properties.getUpstream().getProviderBaseUrl().get(candidateProvider))) {
                continue;
            }
            result.add(AiRoutingResult.FallbackRouteTarget.builder()
                    .provider(candidateProvider)
                    .providerModel(primaryProviderModel)
                    .upstreamUri(buildChatUri(candidateProvider))
                    .routePolicy(resolveRoutePolicy(candidateProvider))
                    .build());
        }
        return result;
    }

    /**
     * 合并 provider 级与全局级路由策略（超时、重试次数、重试状态码）。
     */
    private AiRoutePolicy resolveRoutePolicy(String provider) {
        AiGatewayProperties.TimeoutRetry timeoutRetry = properties.getTimeoutRetry();
        AiGatewayProperties.RoutePolicy routePolicy = timeoutRetry.getRoutePolicy().get(provider);
        return AiRoutePolicy.builder()
                .requestTimeout(routePolicy != null && routePolicy.getRequestTimeout() != null ? routePolicy.getRequestTimeout() : timeoutRetry.getReadTimeout())
                .maxRetries(routePolicy != null && routePolicy.getMaxRetries() != null ? routePolicy.getMaxRetries() : timeoutRetry.getMaxRetries())
                .retryStatusCodes(routePolicy != null && routePolicy.getRetryStatusCodes() != null && !routePolicy.getRetryStatusCodes().isEmpty()
                        ? routePolicy.getRetryStatusCodes()
                        : timeoutRetry.getRetryStatusCodes())
                .build();
    }

    private record ResolvedModel(String provider, String providerModel) {
    }
}
