package com.nageoffer.shortlink.aigateway.adapter;

import com.alibaba.fastjson2.JSON;
import com.nageoffer.shortlink.aigateway.dto.model.AiCanonicalChatRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class OpenAiCompatibleProviderAdapterTest {

    @Test
    void shouldMapCanonicalRequestToOpenAiPayload() {
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter();
        AiCanonicalChatRequest request = AiCanonicalChatRequest.builder()
                .providerModel("gpt-4o-mini")
                .messages(List.of(Map.of("role", "user", "content", "hello")))
                .stream(false)
                .temperature(0.2)
                .maxTokens(128)
                .build();

        Object payload = adapter.toUpstreamRequest(request);
        String json = JSON.toJSONString(payload);
        Assertions.assertTrue(json.contains("\"model\":\"gpt-4o-mini\""));
        Assertions.assertTrue(json.contains("\"max_tokens\":128"));
    }
}
