package com.nageoffer.shortlink.aigateway.observability;

import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 网关 Redis 指标键的唯一权威。
 * <p>
 * 键布局此前散在三处：{@link AiGatewayMetricsRecorder}（写聚合值）、{@link BillingExportService}（读调用明细）、
 * {@code ProviderHealthScoreService}（另写一份 provider 维度的健康分输入）。它们并不是同一个键，而是同一套
 * <b>键布局</b>：前缀、{@code <model>:<yyyyMMddHH>} 形状、hash 字段名、TTL、latency 成员格式。这类契约一旦漂移
 * 不会有任何编译期报错——最典型的是 {@code ProviderHealthScoreService#loadProviderMetrics} 的兜底分支，
 * 它读的正是记录器写的未限定键；字段名一改，健康分会静默全零，路由悄悄退化成静态，线上只表现为"某天开始选路变差"。
 * <p>
 * 边界：这里只管<b>键与字段名</b>，不合并读写的客户端。记录器走响应式 + Lua（单次往返，位于每请求终态，
 * 不能退化成多命令），健康分走同步客户端 + 弹性线程池（纯诊断读）。把两者合成一个 store 只会让延迟敏感的
 * 写入路径被诊断读的形态牵制。
 */
public final class AiGatewayMetricsKeys {

    /**
     * 本类管辖的指标与调用明细键的公共前缀。
     * <p>
     * 治理链路的键（{@code provider-rpm:} / {@code quota:} / {@code cache:} / {@code semantic:}）各有唯一所有者、
     * 不存在跨类契约，仍由各自类持有，因此这里的键前缀刻意与它们同根但不同支——统一的只是
     * "同一份布局被多个类使用"的部分。
     */
    public static final String PREFIX = "short-link:ai-gateway:";

    /**
     * 调用明细列表键：{@code <prefix><yyyyMMdd>}，值为逐条 {@code AiCallRecord} JSON。
     */
    public static final String CALL_PREFIX = PREFIX + "call:";

    /**
     * 模型维度聚合键：{@code <prefix><model>:<yyyyMMddHH>}，hash。
     */
    public static final String MODEL_METRIC_PREFIX = PREFIX + "metric:model:";

    /**
     * 租户维度聚合键：{@code <prefix><tenantId 或 unknown>:<yyyyMMddHH>}，hash。
     */
    public static final String TENANT_METRIC_PREFIX = PREFIX + "metric:tenant:";

    /**
     * 延迟明细键：{@code <prefix><model>:<yyyyMMddHH>}，zset，score 为延迟毫秒。
     */
    public static final String LATENCY_PREFIX = PREFIX + "metric:latency:";

    /**
     * 聚合 hash 的字段名。记录器用 Lua 写、健康分用同步命令读写同一个 hash，字段名必须逐字一致。
     */
    public static final String FIELD_CALLS = "calls";

    public static final String FIELD_SUCCESS = "success";

    public static final String FIELD_COST = "cost";

    public static final String FIELD_TOKEN_IN = "tokenIn";

    public static final String FIELD_TOKEN_OUT = "tokenOut";

    public static final String FIELD_CACHE_HIT = "cacheHit";

    public static final String FIELD_CACHE_MISS = "cacheMiss";

    public static final String FIELD_CACHE_WRITE = "cacheWrite";

    /**
     * 聚合指标与延迟 zset 的 TTL：只需覆盖"当前小时 + 上一小时"的查询窗口。
     */
    public static final Duration METRIC_TTL = Duration.ofDays(2);

    /**
     * 调用明细的 TTL：账单导出按月回溯，需要比聚合值活得久。
     */
    public static final Duration CALL_TTL = Duration.ofDays(7);

    /**
     * 小时分桶格式，与 {@code <yyyyMMddHH>} 键后缀一致。
     */
    public static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHH");

    private static final String UNKNOWN = "unknown";

    private AiGatewayMetricsKeys() {
    }

    /**
     * 当前小时分桶。调用方应在一次记录内只算一次并复用，避免跨小时边界时三个键落到不同桶。
     */
    public static String currentHour() {
        return LocalDateTime.now().format(HOUR_FORMATTER);
    }

    /**
     * 某一天的调用明细键。
     */
    public static String callKey(LocalDate day) {
        return CALL_PREFIX + day.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    public static String modelMetricKey(String model, String hour) {
        return MODEL_METRIC_PREFIX + model + ":" + hour;
    }

    /**
     * 租户维度键。租户缺失时归入 {@code unknown}，否则会写出 {@code ...:tenant::<hour>} 这种空段键。
     */
    public static String tenantMetricKey(String tenantId, String hour) {
        return TENANT_METRIC_PREFIX + defaultIfBlank(tenantId) + ":" + hour;
    }

    public static String latencyKey(String model, String hour) {
        return LATENCY_PREFIX + model + ":" + hour;
    }

    /**
     * provider 维度的限定模型名（{@code provider:model}）。
     * <p>
     * 健康分按 provider 分别统计，因此复用它作为 {@link #modelMetricKey} / {@link #latencyKey} 的 model 段，
     * 键形如 {@code ...:metric:model:claude:claude-3-5-sonnet:2026091812}；
     * {@link #latencyMemberTimestamp} 取的是最后一段，因此这层嵌套不影响时间戳解析。
     */
    public static String providerModel(String provider, String model) {
        return provider + ":" + model;
    }

    /**
     * 拼一个延迟 zset 成员：{@code <前缀>:<epochMillis>}。
     * <p>
     * 约定"最后一段冒号之后固定是毫秒时间戳"，解析依赖它，所以成员前缀里不能再附加别的内容。
     */
    public static String latencyMember(String prefix, long epochMillis) {
        return prefix + ":" + epochMillis;
    }

    /**
     * 从延迟 zset 成员里取毫秒时间戳；不可解析时返回 null，由调用方决定兜底值。
     */
    public static Long latencyMemberTimestamp(String member) {
        if (!StringUtils.hasText(member)) {
            return null;
        }
        int lastSeparator = member.lastIndexOf(':');
        if (lastSeparator < 0 || lastSeparator == member.length() - 1) {
            return null;
        }
        try {
            return Long.parseLong(member.substring(lastSeparator + 1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 租户缓存事件类型 -> 聚合 hash 字段名；未知事件返回 null。
     */
    public static String cacheEventField(String eventType) {
        if (!StringUtils.hasText(eventType)) {
            return null;
        }
        return switch (eventType) {
            case "hit" -> FIELD_CACHE_HIT;
            case "miss" -> FIELD_CACHE_MISS;
            case "write" -> FIELD_CACHE_WRITE;
            default -> null;
        };
    }

    /**
     * 缺失维度值统一归入 {@code unknown}，避免 Prometheus 标签与键出现空段。
     */
    public static String safe(String value) {
        return defaultIfBlank(value);
    }

    private static String defaultIfBlank(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
