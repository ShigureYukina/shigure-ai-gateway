package com.nageoffer.shortlink.aigateway.observability;

import lombok.Builder;
import lombok.Data;

/**
 * 一次请求链路上的阶段事件。
 * <p>
 * 与 span 的区别：span 是给追溯系统用的（事后、可采样、跨进程），
 * 这个事件是给"实时看板"用的——客户端发起到上游返回的每一段都要能立刻看见，
 * 所以字段刻意扁平，前端拿到就能直接画时间线。
 */
@Data
@Builder
public class AiRequestTraceEvent {

    public static final String STAGE_ROUTING = "routing";

    public static final String STAGE_CACHE = "cache";

    public static final String STAGE_QUOTA = "quota";

    public static final String STAGE_UPSTREAM = "upstream";

    public static final String STAGE_FIRST_TOKEN = "first-token";

    public static final String STAGE_FALLBACK = "fallback";

    public static final String STAGE_COMPLETED = "completed";

    public static final String STAGE_FAILED = "failed";

    private String requestId;

    /**
     * 阶段标识，取值见本类的 STAGE_* 常量。
     */
    private String stage;

    /**
     * 该阶段的结果：ok / hit / miss / error / rejected 等，由发布方定义。
     */
    private String status;

    private String provider;

    private String model;

    /**
     * 路由来源、失败原因等人类可读的补充信息。
     */
    private String detail;

    private String tenantId;

    private String appId;

    private Boolean stream;

    /**
     * 从请求开始到该阶段的耗时。
     */
    private Long latencyMillis;

    private Long timestamp;
}
