package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayRoutingProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.upstream.OutboundUrlValidator;
import com.nageoffer.shortlink.aigateway.upstream.UpstreamUrlSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.util.DigestUtils;
import java.util.LinkedHashMap;

@Slf4j
@Service
/**
 * Provider 路由解析服务。
 * <p>
 * 根据请求头与模型别名决定目标 provider、目标模型与上游地址，
 * 同时合并 provider 级与全局超时/重试策略。
 */
public class ProviderRoutingService {

    private final AiGatewayProperties properties;

    private final ProviderHealthScoreService providerHealthScoreService;

    private final ProviderGroupService providerGroupService;

    private final ChannelHealthView channelHealthView;

    @Autowired
    public ProviderRoutingService(AiGatewayProperties properties,
                                  ProviderHealthScoreService providerHealthScoreService,
                                  ProviderGroupService providerGroupService,
                                  ChannelHealthView channelHealthView) {
        this.properties = properties;
        this.providerHealthScoreService = providerHealthScoreService;
        this.providerGroupService = providerGroupService;
        this.channelHealthView = channelHealthView;
    }

    /**
     * 不消费渠道健康状态的构造方式：等价于探测未启用时的旧行为。
     * <p>
     * 保留 3 参构造不是为了"少改测试"，而是因为"这个实例不做健康过滤"是一个真实且合法的组合
     * （未接 DB、未开探测）。
     */
    public ProviderRoutingService(AiGatewayProperties properties,
                                  ProviderHealthScoreService providerHealthScoreService,
                                  ProviderGroupService providerGroupService) {
        this(properties, providerHealthScoreService, providerGroupService, ChannelHealthView.allowAll());
    }

    /**
     * 解析本次请求的完整路由结果。
     */
    public AiRoutingResult resolve(String clientModel, HttpHeaders headers) {
        ProviderSelection providerSelection = resolveProvider(headers, clientModel);
        String provider;
        String providerModel;
        if (providerSelection.member() == null) {
            ModelAliasResolver.Alias alias = ModelAliasResolver.resolve(
                    properties.getUpstream().getModelAlias(), clientModel, providerSelection.provider());
            provider = alias.provider();
            providerModel = alias.model();
        } else {
            // 组路由：通道由组决定，别名只改模型名，不改写 provider。
            // 组是运维显式绑定的通道选择（见 resolveProvider），可信度高于别名里的通道声明；
            // 反过来让别名覆盖组，就会让"这个模型的通道已经钉死了"这条声明失效。
            provider = providerSelection.member().provider();
            providerModel = StringUtils.hasText(providerSelection.member().model())
                    ? providerSelection.member().model()
                    : ModelAliasResolver.resolve(properties.getUpstream().getModelAlias(), clientModel, provider).model();
        }
        String upstreamUri = buildChatUri(provider);
        boolean abHit = isAbHit(headers, clientModel);
        String routeSource = providerSelection.routeSource();

        List<AiRoutingResult.FallbackRouteTarget> fallbackCandidates = providerSelection.groupName() == null
                ? resolveFallbackCandidates(provider, providerModel)
                : resolveGroupFallbackCandidates(providerSelection.groupName(), provider, providerModel, clientModel);
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
                "abPercentage", properties.getRouting().getAbPercentage(),
                "dynamicRoutingEnabled", properties.getRouting().isDynamicRoutingEnabled(),
                "routingStrategy", properties.getRouting().getRoutingStrategy()
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

        Object dynamicRoutingEnabled = requestParam.get("dynamicRoutingEnabled");
        if (dynamicRoutingEnabled instanceof Boolean dynamicRoutingEnabledValue) {
            properties.getRouting().setDynamicRoutingEnabled(dynamicRoutingEnabledValue);
        }

        Object routingStrategy = requestParam.get("routingStrategy");
        if (routingStrategy instanceof String routingStrategyValue && StringUtils.hasText(routingStrategyValue)) {
            try {
                properties.getRouting().setRoutingStrategy(AiGatewayRoutingProperties.RoutingStrategy.valueOf(
                        routingStrategyValue.trim().replace('-', '_').toUpperCase()
                ));
            } catch (IllegalArgumentException ignored) {
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
                // 先校验再落内存：这里是持久化型 SSRF 的写入口（见 requireSafeFor）
                requireSafeFor(normalizedMap);
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

    /**
     * A/B 分桶值，供控制台预览与仿真共用。分桶只依赖请求头与模型名，与路由决策无关。
     * <p>
     * 路由预览不在这里拼装：预览必须与真实链路吃同一套租户策略，因此上移到了
     * {@link RoutingPlanResolver}，避免"预览算一遍、主链路再算一遍"。
     */
    public int abBucket(String clientModel, HttpHeaders headers) {
        return resolveBucket(headers, clientModel);
    }

    public List<ProviderHealthScore> providerHealthScores(String model) {
        return providerHealthScoreService.getProviderScores(model);
    }

    /**
     * 把一次调用的真实结果反馈给 provider 健康分，作为动态路由的排名依据。
     * <p>
     * 回退链路上每个尝试各上报一次：只上报最终结果的话，失败的主通道永远拿不到失败记录，
     * 动态路由也就无法把它排下去。
     */
    public void recordProviderOutcome(String provider, String model, long latencyMillis, boolean success, long tokenIn, long tokenOut) {
        providerHealthScoreService.recordProviderMetricsAsync(provider, model, latencyMillis, success, tokenIn, tokenOut);
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
     * 选通道。四类来源（A/B、请求头显式指定、通道组、动态路由、默认通道）都要过健康视图，
     * 但"被拦下之后怎么办"刻意不同 —— 见 {@link #selectProvider}。
     * <p>
     * 若所有通道都被判不可用，这里会<b>忽略禁用状态重算一次</b>并打 WARN：
     * 探测误判（或运维把通道全禁了）不该让网关对客户端返回 503，
     * 打一个"可能不健康"的通道也比彻底不可用好。这就是决策里的"保底放行"。
     */
    private ProviderSelection resolveProvider(HttpHeaders headers, String clientModel) {
        ProviderSelection selection = selectProvider(headers, clientModel, channelHealthView);
        if (selection != null) {
            return selection;
        }
        ProviderSelection fallback = selectProvider(headers, clientModel, ChannelHealthView.allowAll());
        if (fallback != null) {
            log.warn("all channels are disabled by channel health, falling back to {} ({}) ignoring disabled state;"
                            + " check GET /v1/routing/channels/health",
                    fallback.provider(), fallback.routeSource());
            return fallback;
        }
        return new ProviderSelection(properties.getUpstream().getDefaultProvider(), "default");
    }

    /**
     * 按优先级顺序挑一个满足 {@code view} 的通道，挑不到返回 {@code null}（由调用方决定保底策略）。
     */
    private ProviderSelection selectProvider(HttpHeaders headers, String clientModel, ChannelHealthView view) {
        if (isAbHit(headers, clientModel)) {
            String abProvider = properties.getRouting().getAbProvider();
            if (StringUtils.hasText(abProvider) && StringUtils.hasText(properties.getUpstream().getProviderBaseUrl().get(abProvider))) {
                // A/B 是自动分流：A/B 通道不可用时<b>退回常规路由</b>而不是报错 ——
                // 实验组挂了不该让对照组也拿不到响应
                if (view.allows(abProvider)) {
                    return new ProviderSelection(abProvider, "ab");
                }
                log.debug("ab provider {} is disabled by channel health, fall back to normal routing", abProvider);
            }
        }
        String headerProvider = headers.getFirst("X-AI-Provider");
        if (StringUtils.hasText(headerProvider)) {
            // 显式指定是唯一会"报错而不是换路"的分支：调用方点名某个通道（对比测试、定向排查），
            // 悄悄换掉会让它拿到的结论完全错误。注意放在保底重算之前，
            // 所以"全被禁用"时的保底放行不会绕过这里的判断。
            if (!view.allows(headerProvider)) {
                throw new AiGatewayClientException(AiGatewayErrorCode.PROVIDER_DISABLED,
                        "渠道 " + headerProvider + " 当前不可用（被探测判为故障或已被禁用）");
            }
            return new ProviderSelection(headerProvider, "header");
        }
        // 模型绑定了通道组时，组是权威的通道选择（显式绑定的优先级高于动态路由）。
        // 组内成员已在 ProviderGroupService 里按同一个 view 过滤过。
        ProviderGroupService.GroupSelection groupSelection = providerGroupService.selectForModel(clientModel);
        if (groupSelection != null) {
            ProviderGroupService.GroupMember primary = groupSelection.order().get(0);
            return new ProviderSelection(primary.provider(),
                    ProviderGroupService.routeSourceOf(groupSelection.strategy()),
                    groupSelection.groupName(),
                    primary);
        }
        if (properties.getRouting().isDynamicRoutingEnabled()) {
            String dynamicProvider = providerHealthScoreService.getBestProvider(clientModel);
            if (StringUtils.hasText(dynamicProvider)) {
                String routeSource = switch (properties.getRouting().getRoutingStrategy()) {
                    case COST_OPTIMIZED -> "dynamic-cost";
                    case LATENCY_OPTIMIZED -> "dynamic-latency";
                    case DYNAMIC, STATIC -> "dynamic";
                };
                // 健康分候选集本身已经过 view 过滤，这里再判一次是防"健康分服务用的是 allowAll"的组合
                if (view.allows(dynamicProvider)
                        && StringUtils.hasText(properties.getUpstream().getProviderBaseUrl().get(dynamicProvider))) {
                    return new ProviderSelection(dynamicProvider, routeSource);
                }
            }
        }
        String defaultProvider = properties.getUpstream().getDefaultProvider();
        if (StringUtils.hasText(defaultProvider) && view.allows(defaultProvider)) {
            return new ProviderSelection(defaultProvider, "default");
        }
        return null;
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
     * 归一化 baseUrl 与补全路径的规则统一在 {@link UpstreamUrlSupport}，
     * 这里只负责"从配置里取地址"与"配置缺失时快速失败"。
     */
    private static final String DEFAULT_CHAT_PATH = "/v1/chat/completions";

    private String buildChatUri(String provider) {
        String baseUrl = properties.getUpstream().getProviderBaseUrl().get(provider);
        if (!StringUtils.hasText(baseUrl)) {
            throw new AiGatewayClientException(AiGatewayErrorCode.PROVIDER_NOT_CONFIGURED, "未配置Provider地址: " + provider);
        }
        String chatPath = properties.getUpstream().getProviderChatPath().get(provider);
        String uri = UpstreamUrlSupport.join(baseUrl, StringUtils.hasText(chatPath) ? chatPath : DEFAULT_CHAT_PATH);
        try {
            // 热路径不做 DNS（每个请求一次解析不可接受），只拦"拼出来的地址本身就不对"：
            // 非 http(s)、缺主机名，以及 chatPath 里塞 @ 把主机名顶掉
            // （baseUrl + path 手拼的经典问题：https://api.openai.com + @evil.com/x）。
            // "域名解析到内网"那一层由写入口与启动期校验兜住。
            OutboundUrlValidator.requireWellFormed(uri);
        } catch (IllegalArgumentException ex) {
            throw new AiGatewayClientException(AiGatewayErrorCode.PROVIDER_NOT_CONFIGURED,
                    "Provider地址不合法: " + provider + "（" + ex.getMessage() + "）");
        }
        return uri;
    }

    /**
     * 持久化型 SSRF 的写入口：{@code providerBaseUrl} 会被拼成上游请求地址，
     * 一旦落库就是长期可用的跳板（比一次性的"测试连接"严重得多）。
     * <p>
     * 任一项不合法就拒掉<b>整笔</b>请求，而不是静默跳过非法项：跳过会让调用方收到 200、
     * 以为地址改成功了，实际库里还是旧值 —— 排查成本远高于直接报错。
     * <p>
     * 校验的是<b>最终会被请求的那个地址</b>（baseUrl 拼上 providerChatPath），而不是 baseUrl 本身：
     * 这样路径里出现非法字符（{@code URI.create} 会抛）也能在写入口发现，
     * 且一个渠道只解析一次 DNS。host 只可能来自 baseUrl（{@code UpstreamUrlSupport.join}
     * 保证拼接处一定有前导斜杠，path 里的 {@code @} 不会变成 userinfo），
     * 所以报错时把责任归到 {@code providerBaseUrl} 是准确的。
     */
    private void requireSafeFor(Map<String, String> providerBaseUrls) {
        boolean allowPrivateAddresses = properties.getSecurity().getSsrf().isAllowPrivateAddresses();
        providerBaseUrls.forEach((provider, baseUrl) -> {
            String target = UpstreamUrlSupport.join(baseUrl, properties.getUpstream().getProviderChatPath().get(provider));
            try {
                OutboundUrlValidator.requireSafe(target, allowPrivateAddresses);
            } catch (IllegalArgumentException ex) {
                throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST,
                        "providerBaseUrl[" + provider + "] 不合法：" + ex.getMessage());
            }
        });
    }

    private List<AiRoutingResult.FallbackRouteTarget> resolveFallbackCandidates(String primaryProvider, String primaryProviderModel) {
        if (!properties.getRouting().isFallbackEnabled()) {
            return List.of();
        }
        List<AiRoutingResult.FallbackRouteTarget> result = new ArrayList<>();
        for (String candidateProvider : RouteCandidates.configured(properties, channelHealthView)) {
            if (candidateProvider.equals(primaryProvider)) {
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
     * 组内回退链：组内其余成员按策略顺序排列，各自带上自己的模型名。
     * <p>
     * 这条链不看 {@code fallback-enabled}：组本身就是"这些通道互为备份"的声明，
     * 若再受全局开关约束，PRIORITY 这类策略的"降级"语义就落空了。
     */
    private List<AiRoutingResult.FallbackRouteTarget> resolveGroupFallbackCandidates(String groupName,
                                                                                   String primaryProvider,
                                                                                   String primaryProviderModel,
                                                                                   String clientModel) {
        List<AiRoutingResult.FallbackRouteTarget> result = new ArrayList<>();
        Set<String> configured = RouteCandidates.configured(properties, channelHealthView);
        for (ProviderGroupService.GroupMember member : providerGroupService.orderedMembers(groupName, clientModel)) {
            if (member.provider().equals(primaryProvider)) {
                continue;
            }
            if (!configured.contains(member.provider())) {
                continue;
            }
            result.add(AiRoutingResult.FallbackRouteTarget.builder()
                    .provider(member.provider())
                    .providerModel(StringUtils.hasText(member.model()) ? member.model() : primaryProviderModel)
                    .upstreamUri(buildChatUri(member.provider()))
                    .routePolicy(resolveRoutePolicy(member.provider()))
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

    private record ProviderSelection(String provider, String routeSource, String groupName, ProviderGroupService.GroupMember member) {

        ProviderSelection(String provider, String routeSource) {
            this(provider, routeSource, null, null);
        }
    }
}
