package com.nageoffer.shortlink.aigateway.runtime;

import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsKeys;

/**
 * 运行时配置的 Redis 键。
 * <p>
 * 复用指标键的公共前缀（{@link AiGatewayMetricsKeys#PREFIX}），并遵守它的边界约定：
 * 这里只管键与字段名，不合并读写的客户端（版本号全程走同步客户端，因为轮询与写入都是低频动作，
 * 而 {@code AiGatewayMetricsKeys} 管辖的那批键位于每请求路径上）。
 */
public final class RuntimeConfigKeys {

    /**
     * 版本号 hash：{@code field = 域名}，{@code value = 版本令牌}。
     * <p>
     * 与指标键不同，这里<b>刻意不设 TTL</b>：版本号是"要不要重载"的判据，一旦过期，
     * 各实例会退回"没有版本"的状态并重新加载一遍，属于无意义抖动。
     */
    public static final String VERSION_KEY = AiGatewayMetricsKeys.PREFIX + "runtime-config:version";

    /**
     * 单调递增序号，用来生成互不相同的新版本令牌。
     */
    public static final String SEQ_KEY = AiGatewayMetricsKeys.PREFIX + "runtime-config:seq";

    private RuntimeConfigKeys() {
    }
}
