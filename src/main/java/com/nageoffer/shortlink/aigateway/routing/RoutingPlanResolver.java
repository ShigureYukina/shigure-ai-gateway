package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import com.nageoffer.shortlink.aigateway.tenant.TenantModelPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由决策的唯一入口。
 * <p>
 * 主链路与控制台预览原先各自拼装决策：主链路先过租户模型策略再路由，
 * 预览则直接路由，于是"控制台预览出来的通道"和"请求实际落到的通道"可以不一致——
 * 而预览的全部价值就在于它和真实链路一致。
 * <p>
 * 顺序不可交换：<b>先定模型，再定通道</b>。租户映射可能把 {@code gpt-4} 换成 {@code claude-3-5-sonnet}，
 * 反过来先选通道就会选中一个根本承载不了这个模型的渠道。
 */
@Component
@RequiredArgsConstructor
public class RoutingPlanResolver {

    private final AiGatewayProperties properties;

    private final ProviderRoutingService providerRoutingService;

    private final TenantModelPolicyService tenantModelPolicyService;

    /**
     * 解析本次请求的完整路由方案。
     *
     * @param tenantContext 为 {@code null} 表示不按租户维度解析（通道维度预览、内部调用）
     * @return 生效模型 + 路由结果；生效模型必须回写给调用方，它是缓存键与上游请求体的依据
     */
    public RoutingPlan resolve(TenantContext tenantContext, String clientModel, HttpHeaders headers) {
        String effectiveModel = tenantContext == null
                ? clientModel
                : tenantModelPolicyService.resolveModel(tenantContext, clientModel);
        return new RoutingPlan(effectiveModel, providerRoutingService.resolve(effectiveModel, headers));
    }

    /**
     * 把一次调用的真实结果反馈给动态路由，供下一次决策使用。
     */
    public void recordOutcome(String provider, String providerModel, long latencyMillis,
                              boolean success, long tokenIn, long tokenOut) {
        providerRoutingService.recordProviderOutcome(provider, providerModel, latencyMillis, success, tokenIn, tokenOut);
    }

    /**
     * 路由预览。
     *
     * @param tenantId 传了就按该租户的模型策略解析（与真实链路一致）；不传则只做通道维度预览
     */
    public Map<String, Object> preview(String model, HttpHeaders headers, String tenantId) {
        TenantContext tenantContext = StringUtils.hasText(tenantId)
                ? new TenantContext(tenantId, null, null)
                : null;
        RoutingPlan plan = resolve(tenantContext, model, headers);
        AiRoutingResult routing = plan.routing();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", plan.effectiveModel());
        // "有没有查租户策略"与"策略有没有命中"是两件事：租户未配策略时这里仍为 true
        result.put("tenantScoped", tenantContext != null);
        result.put("provider", routing.getProvider());
        result.put("providerModel", routing.getProviderModel());
        result.put("upstreamUri", routing.getUpstreamUri());
        result.put("routeSource", routing.getRouteSource());
        result.put("abHit", routing.getAbHit());
        result.put("abBucket", providerRoutingService.abBucket(plan.effectiveModel(), headers));
        result.put("abThreshold", properties.getRouting().getAbPercentage());
        result.put("dynamicRoutingEnabled", properties.getRouting().isDynamicRoutingEnabled());
        result.put("routingStrategy", properties.getRouting().getRoutingStrategy());
        result.put("routePolicy", routing.getRoutePolicy());
        result.put("fallbackCandidates",
                routing.getFallbackCandidates() == null ? List.of() : routing.getFallbackCandidates());
        return result;
    }

    /**
     * 一次路由决策的结果。
     *
     * @param effectiveModel 租户策略映射后的模型名；缓存键与上游请求体都用它
     * @param routing        通道、上游地址与回退链
     */
    public record RoutingPlan(String effectiveModel, AiRoutingResult routing) {
    }
}
