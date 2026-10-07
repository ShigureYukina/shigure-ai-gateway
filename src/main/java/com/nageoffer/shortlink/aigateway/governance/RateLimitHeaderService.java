package com.nageoffer.shortlink.aigateway.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流响应头中转站。
 * <p>
 * 配额用量在预检时刻落快照（{@code record}），响应头只能在响应提交前写入，
 * 因此用 requestId 作为关联键，由 {@code beforeCommit} 回调取走（{@code consume}）。
 * <p>
 * 快照必须早于上游调用记录：结算（尤其 SSE 的流结束）发生在响应提交之后，
 * 那时再记，{@code consume} 永远扑空，条目也会在长跑进程里无限堆积。
 * 另加 TTL 兜底：客户端断连等异常路径可能跳过 {@code consume}，
 * 过期快照由下一次 {@code record} 触发的清理回收。
 */
@Slf4j
@Component
public class RateLimitHeaderService {

    /** 快照数量上限：超过先清 TTL 过期条目，仍超则整体清空（宁可丢响应头，不可丢内存） */
    private static final int MAX_ENTRIES = 8192;

    private static final long ENTRY_TTL_MS = 300_000;

    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    private record Snapshot(Map<String, String> headers, long createdAt) {
    }

    public void record(String requestId, Map<String, String> headers) {
        if (!StringUtils.hasText(requestId) || headers == null || headers.isEmpty()) {
            return;
        }
        if (snapshots.size() >= MAX_ENTRIES && purgeExpired() >= MAX_ENTRIES) {
            log.warn("rate-limit header snapshots exceeded {}, clearing all in-flight snapshots", MAX_ENTRIES);
            snapshots.clear();
        }
        snapshots.put(requestId, new Snapshot(headers, System.currentTimeMillis()));
    }

    public Map<String, String> consume(String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return Map.of();
        }
        Snapshot snapshot = snapshots.remove(requestId);
        return snapshot == null ? Map.of() : snapshot.headers();
    }

    private int purgeExpired() {
        long deadline = System.currentTimeMillis() - ENTRY_TTL_MS;
        snapshots.values().removeIf(snapshot -> snapshot.createdAt() < deadline);
        return snapshots.size();
    }

    /**
     * 组装 OpenAI 风格的 token 限流响应头。
     * <p>
     * 只暴露 token 维度：当前网关的限流本身就是 token 配额，
     * 补一个 requests 维度会变成"看起来有、其实是编的"。
     */
    public Map<String, String> buildTokenHeaders(long minuteQuota, long minuteUsed, long dayQuota, long dayUsed) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-ratelimit-limit-tokens", String.valueOf(Math.max(0L, minuteQuota)));
        headers.put("x-ratelimit-remaining-tokens", String.valueOf(Math.max(0L, minuteQuota - minuteUsed)));
        headers.put("x-ratelimit-limit-tokens-day", String.valueOf(Math.max(0L, dayQuota)));
        headers.put("x-ratelimit-remaining-tokens-day", String.valueOf(Math.max(0L, dayQuota - dayUsed)));
        return headers;
    }
}
