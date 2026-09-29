package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 渠道健康状态：主动探测的真源。
 * <p>
 * 三种"不可用"要分清，它们的运维动作完全不同：
 * <ul>
 *   <li>{@link #status} = {@code DOWN}：探测的自动结论，冷却到期后会被下一次探测改回 UP；</li>
 *   <li>{@link #manualDisabled}：运维在控制台上的手动禁用，<b>探测绝不覆盖</b>
 *       （SQL 里的 {@code manual_disabled = 0} 守卫就是为了这条）；</li>
 *   <li>yml 侧 {@code probe.channel-enabled.*}：静态配置，属"跟着发布走"的禁用，不在这张表里。</li>
 * </ul>
 * 没有行的渠道按 {@code UP + 未禁用} 处理（fail-open）：漏一行不该等于静默禁用，
 * 第一次探测后自然会写入该行。
 */
@Data
@Table("provider_health")
public class ProviderHealthEntity {

    /**
     * 与 {@code provider_health.status} 的取值一一对应。
     * SQL 文本里没法引用 Java 常量，所以字符串在仓储的语句里是硬编码的，改动时两边要一起看。
     */
    public static final String STATUS_UP = "UP";

    public static final String STATUS_DOWN = "DOWN";

    @Id
    private Long id;

    @Column("provider")
    private String provider;

    @Column("status")
    private String status;

    @Column("consecutive_failures")
    private Integer consecutiveFailures;

    @Column("consecutive_successes")
    private Integer consecutiveSuccesses;

    @Column("manual_disabled")
    private Boolean manualDisabled;

    @Column("disabled_until")
    private LocalDateTime disabledUntil;

    @Column("last_error")
    private String lastError;

    @Column("last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column("last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column("last_failure_at")
    private LocalDateTime lastFailureAt;

    @Column("latency_millis")
    private Long latencyMillis;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    public boolean isDown() {
        return STATUS_DOWN.equals(status);
    }

    public boolean isManuallyDisabled() {
        return Boolean.TRUE.equals(manualDisabled);
    }

    /**
     * 冷却是否已过（未设冷却视为已过）。只有为真时才重新探测 DOWN 的渠道。
     */
    public boolean cooldownElapsed() {
        return disabledUntil == null || !disabledUntil.isAfter(LocalDateTime.now());
    }
}
