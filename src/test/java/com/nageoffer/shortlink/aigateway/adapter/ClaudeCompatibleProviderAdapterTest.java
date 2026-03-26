package com.nageoffer.shortlink.aigateway.adapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nageoffer.shortlink.aigateway.dto.model.AiCanonicalChatRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class ClaudeCompatibleProviderAdapterTest {

    @Test
    void shouldNormalizeClaudeResponseToOpenAiShape() {
        ClaudeCompatibleProviderAdapter adapter = new ClaudeCompatibleProviderAdapter();
        AiCanonicalChatRequest request = AiCanonicalChatRequest.builder()
                .clientModel("claude-3-5-sonnet-compatible")
                .providerModel("claude-3-5-sonnet-latest")
                .messages(List.of(Map.of("role", "user", "content", "hello")))
                .stream(false)
                .build();

        String upstream = "{\"id\":\"msg_1\",\"content\":[{\"text\":\"world\"}]}";
        String normalized = adapter.fromUpstreamResponse(upstream, request).block();
        JSONObject jsonObject = JSON.parseObject(normalized);
        Assertions.assertEquals("chat.completion", jsonObject.getString("object"));
        Assertions.assertEquals("claude-3-5-sonnet-compatible", jsonObject.getString("model"));
        Assertions.assertEquals("world", jsonObject.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content"));
    }
}
