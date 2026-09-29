package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 候选通道集合的唯一拼装入口。
 * <p>
 * "平台上有哪些通道可用"这个问题原先在 {@link ProviderRoutingService} 的 fallback 链路、
 * {@code ProviderHealthScoreService} 的健康分候选、以及组内成员过滤处各答了一遍，
 * 且判定口径不一致（有的看 baseUrl 非空、有的只看 key 存在）。这里统一为：
 * <p>
 * <b>只有 baseUrl 非空的通道算可用</b>——baseUrl 为空时 {@code buildChatUri} 必然抛
 * {@code PROVIDER_NOT_CONFIGURED}，把它算作候选只会让回退链多一次注定失败的尝试。
 * <p>
 * 顺序：provider-priority 的声明顺序 -> 其余已配置通道 -> 默认通道。
 * 用 {@link LinkedHashSet} 保留声明顺序并去重，回退链的顺序才是确定的。
 */
public final class RouteCandidates {

    private RouteCandidates() {
    }

    public static Set<String> configured(AiGatewayProperties properties) {
        Set<String> providers = new LinkedHashSet<>();
        List<String> priority = properties.getRouting().getProviderPriority();
        if (priority != null) {
            providers.addAll(priority);
        }
        Map<String, String> baseUrls = properties.getUpstream().getProviderBaseUrl();
        if (baseUrls == null) {
            return Set.of();
        }
        providers.addAll(baseUrls.keySet());
        if (StringUtils.hasText(properties.getUpstream().getDefaultProvider())) {
            providers.add(properties.getUpstream().getDefaultProvider());
        }
        providers.removeIf(provider -> !StringUtils.hasText(baseUrls.get(provider)));
        return providers;
    }

    /**
     * 在"已配置"的基础上再滤掉渠道健康视图判定为不可用的通道。
     * <p>
     * 保留上面那个只吃 properties 的重载，是为了不动既有调用点与测试 ——
     * "有哪些通道被配置了"与"这些通道里哪些现在能用"是两个问题，
     * 前者是静态配置事实，后者含运行时状态（探测结论 + 人工禁用），混在一起会让
     * 所有只关心配置的调用方都被迫接一个 view。
     * <p>
     * {@code view} 为 null 表示不做健康过滤（等价于旧行为）。
     */
    public static Set<String> configured(AiGatewayProperties properties, ChannelHealthView view) {
        Set<String> candidates = configured(properties);
        if (view == null) {
            return candidates;
        }
        candidates.removeIf(provider -> !view.allows(provider));
        return candidates;
    }
}
