package com.nageoffer.shortlink.aigateway.probe;

import java.time.Instant;
import java.util.List;

/**
 * 一轮探测的汇总结果。
 * <p>
 * 四个计数刻意分开而不是"成功/失败"两分：
 * <ul>
 *   <li>{@code probed} —— 真的发了请求的渠道数；</li>
 *   <li>{@code healthy} / {@code unhealthy} —— 其中通与不通的；</li>
 *   <li>{@code inconclusive} —— 探到了但结论不算数（429 限流、没配凭证）。
 *       它们既不加失败计数也不清零，所以必须与 {@code unhealthy} 分开报，
 *       否则运维看到"3 个渠道失败"会去查一个其实只是被限流的渠道。</li>
 * </ul>
 * {@code skipped} 是根本没探的渠道（冷却中 / 已手动禁用），带原因字符串。
 *
 * @param probed       真的发出探测请求的渠道数
 * @param healthy      探测通过的渠道数
 * @param unhealthy    探测失败且已计入失败次数的渠道数
 * @param inconclusive 探到了但结论不算数的渠道数（限流、未配凭证）
 * @param skipped      "provider: 原因" 形式，表示本轮没探的渠道
 * @param disabled     本轮内发生 UP→DOWN 迁移的渠道
 * @param recovered    本轮内发生 DOWN→UP 迁移的渠道
 * @param skippedByLock 整轮被跳过（别的实例正在探 / 本实例重入）
 * @param finishedAt   本轮结束时间
 */
public record ProbeSummary(int probed,
                           int healthy,
                           int unhealthy,
                           int inconclusive,
                           List<String> skipped,
                           List<String> disabled,
                           List<String> recovered,
                           boolean skippedByLock,
                           Instant finishedAt) {

    public ProbeSummary {
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
        disabled = disabled == null ? List.of() : List.copyOf(disabled);
        recovered = recovered == null ? List.of() : List.copyOf(recovered);
    }

    /**
     * 整轮被跳过（跨实例锁被别人持有，或本实例已有探测在跑）。
     * 不算异常：多实例部署下每个周期只需要一个实例真的去打上游。
     */
    public static ProbeSummary held() {
        return new ProbeSummary(0, 0, 0, 0, List.of(), List.of(), List.of(), true, Instant.now());
    }
}
