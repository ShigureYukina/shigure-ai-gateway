package com.nageoffer.shortlink.aigateway.governance;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class UsageExtractor {

    public Long extractTotalTokens(String body) {
        UsageDetail usageDetail = extractUsage(body);
        return usageDetail == null ? null : usageDetail.getTotalTokens();
    }

    public UsageDetail extractUsage(String body) {
        try {
            JSONObject jsonObject = JSON.parseObject(body);
            if (jsonObject == null) {
                return null;
            }
            JSONObject usage = jsonObject.getJSONObject("usage");
            if (usage == null) {
                return null;
            }
            return UsageDetail.builder()
                    .promptTokens(usage.getLong("prompt_tokens"))
                    .completionTokens(usage.getLong("completion_tokens"))
                    .totalTokens(usage.getLong("total_tokens"))
                    .build();
        } catch (Exception ignore) {
            return null;
        }
    }
}
