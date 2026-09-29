package com.nageoffer.shortlink.aigateway.config;

import lombok.Data;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 主动探测（渠道健康）属性组：对应 {@code short-link.ai-gateway.probe.*}。
 * <p>
 * 绑定入口仍是 {@link AiGatewayProperties}——本类不注册为独立配置 Bean，
 * 只做属性分组，因此 yml 键与注入点都不变。
 * <p>
 * 注意 {@code interval} / {@code initialDelay} 会被 {@code ChannelProbeScheduler} 的
 * {@code @Scheduled} <b>直接读同一份配置</b>，所以必须是 ISO-8601（{@code PT5M}）；
 * 写成 {@code 5m} 会导致启动失败（本项目已经踩过一次：见 {@code GatewaySyncScheduler}）。
 * {@code AiGatewayApplicationContextTest} 里有绑定断言兜住这件事。
 */
@Data
public class AiGatewayProbeProperties {

    /**
     * 是否启用定时探测。
     * <p>
     * 关掉之后渠道禁用状态只能靠管理接口手工维护（{@code manual_disabled}），
     * 自动禁用/恢复不再发生。
     */
    private boolean enabled = true;

    /**
     * 探测周期。同时也是跨实例 Redis 锁的 TTL 依据（取 0.9 倍）。
     */
    private Duration interval = Duration.ofMinutes(5);

    /**
     * 首次探测延迟：给启动期的配置加载与凭证加载留出时间，避免刚起来就判上游不可用。
     */
    private Duration initialDelay = Duration.ofSeconds(60);

    /**
     * 单次探测超时。
     */
    private Duration timeout = Duration.ofSeconds(10);

    /**
     * 连续失败到达这个次数才自动禁用。取 3 是为了躲开单次网络抖动 —— 探测误判的代价是
     * 一个健康渠道被踢出候选集，比"晚几分钟发现真的挂了"要贵得多。
     */
    private int failureThreshold = 3;

    /**
     * 自动禁用后的冷却时间。冷却期内不探测（避免对已经宕机的上游持续打请求），
     * 到期后才重新探，连续成功达 {@link #recoveryThreshold} 次才恢复。
     */
    private Duration downCooldown = Duration.ofMinutes(5);

    /**
     * 连续成功到达这个次数才恢复。比失败阈值低是刻意的：
     * 恢复只影响"能不能再被选中"，多恢复一次的成本远低于把渠道永久关在门外。
     */
    private int recoveryThreshold = 2;

    /**
     * yml 侧的静态禁用清单：{@code probe.channel-enabled.claude=false}。
     * <p>
     * 与 {@code provider_health.manual_disabled} 的区别是"配置"与"运行时状态"：
     * 这个开关跟着发布走，控制台看不到也改不了；{@code manual_disabled} 是运维在控制台上的临时动作。
     * 两者任一为真都会让渠道不可路由，但只有后者能在不提版本的情况下立即改。
     */
    private Map<String, Boolean> channelEnabled = new HashMap<>();
}
