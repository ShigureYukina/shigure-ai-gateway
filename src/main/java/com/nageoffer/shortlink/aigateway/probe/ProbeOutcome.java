package com.nageoffer.shortlink.aigateway.probe;

import java.util.List;

/**
 * 一次探测的结论。
 * <p>
 * 是 record 而不是实体：它是"这一次探测看到了什么"的瞬时事实，
 * 落库的那部分（连续失败/成功次数、状态、冷却）由 {@code provider_health} 单独维护，
 * 两者刻意不共用类型 —— 否则很容易把"本轮结果"当成"累积状态"写错。
 * <p>
 * {@link #ok()} 与 {@link #errorType()} 有冗余，是刻意的：调用方最常问的是"行不行"，
 * 让它不必每次都写 {@code errorType() == OK}。
 *
 * @param provider      渠道标识
 * @param ok            本次探测是否成功
 * @param httpStatus    上游 HTTP 状态码；没走到 HTTP 层时为 null
 * @param models        成功时的模型 ID 列表；失败恒为空列表（不是 null）
 * @param latencyMillis 本次探测耗时毫秒
 * @param errorType     成功时为 {@link ProbeErrorType#OK}
 * @param message       人类可读的失败原因；成功时为 null
 */
public record ProbeOutcome(String provider,
                           boolean ok,
                           Integer httpStatus,
                           List<String> models,
                           long latencyMillis,
                           ProbeErrorType errorType,
                           String message) {

    public ProbeOutcome {
        models = models == null ? List.of() : List.copyOf(models);
    }

    public static ProbeOutcome success(String provider, List<String> models, long latencyMillis) {
        return new ProbeOutcome(provider, true, 200, models, latencyMillis, ProbeErrorType.OK, null);
    }

    public static ProbeOutcome failure(String provider, ProbeErrorType errorType, String message,
                                       long latencyMillis) {
        return new ProbeOutcome(provider, false, null, List.of(), latencyMillis, errorType, message);
    }

    public static ProbeOutcome failure(String provider, ProbeErrorType errorType, String message,
                                       long latencyMillis, Integer httpStatus) {
        return new ProbeOutcome(provider, false, httpStatus, List.of(), latencyMillis, errorType, message);
    }

    /**
     * 落库用的原因串：分类 + 状态码。控制台直接展示它来解释"这条渠道为什么被禁用"。
     */
    public String describeError() {
        if (ok) {
            return null;
        }
        return httpStatus == null ? errorType.name() : errorType.name() + " (HTTP " + httpStatus + ")";
    }
}
