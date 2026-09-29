package com.nageoffer.shortlink.aigateway.governance;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 上游用量提取。
 * <p>
 * 适配器的流式转换有义务把上游用量归一为 OpenAI 的 {@code usage} 字段，
 * 因此这里只需要一套解析逻辑即可同时服务非流式与流式两条链路。
 */
@Component
public class UsageExtractor {

    private static final String DONE_PAYLOAD = "[DONE]";

    public Long extractTotalTokens(String body) {
        UsageDetail usageDetail = extractUsage(body);
        return usageDetail == null ? null : usageDetail.getTotalTokens();
    }

    public UsageDetail extractUsage(String body) {
        JSONObject jsonObject = parseJson(body);
        return jsonObject == null ? null : toUsageDetail(jsonObject.getJSONObject("usage"));
    }

    /**
     * 提取单个流式 chunk 中的用量。
     * <p>
     * OpenAI 在 {@code stream_options.include_usage} 打开时会在末尾 chunk 带上 usage；
     * 未命中时返回 null，由调用方决定是继续等待还是按预估值结算。
     */
    public UsageDetail extractStreamChunkUsage(String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        String trimmed = payload.trim();
        if (DONE_PAYLOAD.equals(trimmed) || !trimmed.startsWith("{")) {
            return null;
        }
        JSONObject jsonObject = parseJson(trimmed);
        return jsonObject == null ? null : toUsageDetail(jsonObject.getJSONObject("usage"));
    }

    /**
     * 判断该 chunk 是否为流结束标记。
     */
    public boolean isStreamDone(String payload) {
        return StringUtils.hasText(payload) && DONE_PAYLOAD.equals(payload.trim());
    }

    private UsageDetail toUsageDetail(JSONObject usage) {
        if (usage == null || usage.isEmpty()) {
            return null;
        }
        Long promptTokens = usage.getLong("prompt_tokens");
        Long completionTokens = usage.getLong("completion_tokens");
        Long totalTokens = usage.getLong("total_tokens");
        if (promptTokens == null && completionTokens == null && totalTokens == null) {
            return null;
        }
        return UsageDetail.builder()
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens == null
                        ? (promptTokens == null ? 0L : promptTokens) + (completionTokens == null ? 0L : completionTokens)
                        : totalTokens)
                .build();
    }

    private JSONObject parseJson(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            return JSON.parseObject(body);
        } catch (Exception ignore) {
            return null;
        }
    }
}
