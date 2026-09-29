package com.nageoffer.shortlink.aigateway.runtime;

/**
 * 运行时可变更的配置域。
 * <p>
 * 只列"值来自 {@link com.nageoffer.shortlink.aigateway.config.AiGatewayProperties}、且控制台有写入口"的域。
 * 通道组（{@code provider_group} 三表）与租户配置各有自己的持久化与刷新路径，不走这里。
 * <p>
 * 渠道健康状态复用同一套版本号做跨实例通知，但它的数据源不是 properties，
 * 因此实现 {@link RuntimeConfigReloader} 而不是进这个枚举。
 */
public enum RuntimeConfigDomain {

    ROUTING("routing"),
    RATE_LIMIT("rateLimit"),
    CACHE("cache"),
    SAFETY("safety"),
    PLUGIN("plugin"),
    SECURITY("security");

    private final String key;

    RuntimeConfigDomain(String key) {
        this.key = key;
    }

    /**
     * 落库与版本号里用的标识，同时也是 {@code POST /v1/runtime-config/{domain}} 的路径变量。
     */
    public String key() {
        return key;
    }

    public static RuntimeConfigDomain fromKey(String key) {
        for (RuntimeConfigDomain each : values()) {
            if (each.key.equals(key)) {
                return each;
            }
        }
        throw new IllegalArgumentException("unknown runtime config domain: " + key);
    }
}
