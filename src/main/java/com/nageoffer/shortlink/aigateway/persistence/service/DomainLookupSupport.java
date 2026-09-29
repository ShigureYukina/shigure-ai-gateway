package com.nageoffer.shortlink.aigateway.persistence.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 租户配置各域共用的分层规则。
 * <p>
 * "数据库快照优先、yml 兜底、persistence 关闭时快照整体缺席"这条规则原本在
 * {@code TenantConfigQueryService} 里被每个 finder 各写一遍；拆域之后如果每个域再各写一遍，
 * 就会出现"改了一处漏一处"的隐患，所以收在这里。
 * <p>
 * 注意这里只描述取值优先级，不关心各域的快照长什么样——快照的加载与替换由各域自己负责。
 */
final class DomainLookupSupport {

    private DomainLookupSupport() {
    }

    /**
     * 数据库快照是否参与查询。关闭时所有域都退化为只读 yml。
     */
    static boolean snapshotEnabled(AiGatewayProperties properties) {
        return properties.getTenant().getPersistence().isEnabled();
    }

    /**
     * 快照优先、yml 兜底。
     *
     * @param fromSnapshot 快照命中值，可为 null
     * @param fromYml      yml 取值方式；惰性求值，避免快照命中时白算一次
     */
    static <T> Optional<T> preferSnapshot(AiGatewayProperties properties, T fromSnapshot, Supplier<T> fromYml) {
        if (snapshotEnabled(properties) && fromSnapshot != null) {
            return Optional.of(fromSnapshot);
        }
        return Optional.ofNullable(fromYml.get());
    }
}
