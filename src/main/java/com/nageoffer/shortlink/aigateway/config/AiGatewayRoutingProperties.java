package com.nageoffer.shortlink.aigateway.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由域属性组：对应 {@code short-link.ai-gateway.routing.*}。
 * <p>
 * 绑定入口仍是 {@link AiGatewayProperties}——本类不注册为独立配置 Bean，
 * 只做属性分组，因此 yml 键与注入点都不变。
 */
@Data
public class AiGatewayRoutingProperties {

    /**
     * 是否启用失败回退（主路由失败时尝试候选 provider）
     */
    private boolean fallbackEnabled = false;

    /**
     * provider 优先级顺序，首个可用 provider 作为主路由
     */
    private List<String> providerPriority = new ArrayList<>(List.of("openai", "claude"));

    /**
     * 是否启用 A/B 灰度路由
     */
    private boolean abEnabled = false;

    /**
     * 灰度 provider（B 组）
     */
    private String abProvider = "claude";

    /**
     * B 组流量百分比（0-100）
     */
    private Integer abPercentage = 0;

    /**
     * 是否启用动态路由。
     */
    private boolean dynamicRoutingEnabled = false;

    /**
     * 路由策略：static / dynamic / cost-optimized / latency-optimized
     */
    private RoutingStrategy routingStrategy = RoutingStrategy.STATIC;

    /**
     * 通道组定义（组名 -> 组配置）。
     * <p>
     * yml 只作为兜底种子：运行期真源是 {@code provider_group} 系列表，
     * DB 不可用或表为空时回退到这里，保证本地 mock 链路仍能起来。
     */
    private Map<String, ProviderGroupConfig> providerGroups = new LinkedHashMap<>();

    /**
     * 模型到通道组的绑定（模型名 -> 组名）。
     */
    private Map<String, String> modelGroups = new LinkedHashMap<>();

    /**
     * 通道组配置：一组互为备份的 provider + 组内负载均衡策略。
     */
    @Data
    public static class ProviderGroupConfig {

        /**
         * 组内负载均衡策略，缺省按优先级降级。
         */
        private LoadBalanceStrategy strategy = LoadBalanceStrategy.PRIORITY;

        /**
         * 是否启用该组；停用后绑定到该组的模型回退到默认路由。
         */
        private boolean enabled = true;

        private String description;

        private List<GroupMemberConfig> members = new ArrayList<>();
    }

    /**
     * 通道组成员。
     */
    @Data
    public static class GroupMemberConfig {

        private String provider;

        /**
         * 可选：该 provider 上的真实模型名，留空沿用路由解析结果。
         */
        private String model;

        /**
         * 权重，仅加权策略与同级排序使用。
         */
        private Integer weight = 1;

        /**
         * 优先级，数字越大越优先。
         */
        private Integer priority = 1;

        private boolean enabled = true;
    }

    /**
     * 组内负载均衡策略。
     * <p>
     * PRIORITY / ROUND_ROBIN / RANDOM / WEIGHTED 是配置式分配，
     * DYNAMIC 按真实调用的成功率、延迟、成本动态排序（数据不足时回退优先级序）。
     */
    public enum LoadBalanceStrategy {
        PRIORITY,
        ROUND_ROBIN,
        RANDOM,
        WEIGHTED,
        DYNAMIC
    }

    public enum RoutingStrategy {
        STATIC,
        DYNAMIC,
        COST_OPTIMIZED,
        LATENCY_OPTIMIZED
    }
}
