package com.nageoffer.shortlink.aigateway.config;

import lombok.Data;

import java.time.Duration;

/**
 * 运行时配置中心属性组：对应 {@code short-link.ai-gateway.runtime-config.*}。
 * <p>
 * 绑定入口仍是 {@link AiGatewayProperties}——本类不注册为独立配置 Bean，
 * 只做属性分组，因此 yml 键与注入点都不变。
 */
@Data
public class AiGatewayRuntimeConfigProperties {

    /**
     * 是否启用跨实例同步。
     * <p>
     * 关闭后写入只落在本实例内存，版本号也不再轮询——用于排查同步机制本身的问题，
     * 或者明确只跑单实例的场景。
     */
    private boolean enabled = true;

    /**
     * 轮询版本号的间隔。
     * <p>
     * 由 {@code @Scheduled} 直接读取，必须写成 ISO-8601（如 {@code PT5S}）。
     */
    private Duration pollInterval = Duration.ofSeconds(5);

    /**
     * 首次轮询延迟：给应用启动与首轮 DB 加载留时间，也避免测试跑到轮询任务。
     */
    private Duration pollInitialDelay = Duration.ofSeconds(10);
}
