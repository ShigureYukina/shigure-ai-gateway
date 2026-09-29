package com.nageoffer.shortlink.aigateway.governance;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流响应头中转站。
 * <p>
 * 配额用量在网关服务层结算，而响应头只能在响应提交前写入。两者之间隔着
 * Controller 与编码器，因此用 requestId 作为关联键传递快照。
 * <p>
 * 快照在 {@code beforeCommit} 时被取走（remove），天然随响应生命周期清理，
 * 不会在长跑进程里堆积。
 */
@Component
public class RateLimitHeaderService {

    private final Map<String, Map<String, String>> snapshots = new ConcurrentHashMap<>();

    public void record(String requestId, Map<String, String> headers) {
        if (!StringUtils.hasText(requestId) || headers == null || headers.isEmpty()) {
            return;
        }
        snapshots.put(requestId, headers);
    }

    public Map<String, String> consume(String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return Map.of();
        }
        Map<String, String> headers = snapshots.remove(requestId);
        return headers == null ? Map.of() : headers;
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
