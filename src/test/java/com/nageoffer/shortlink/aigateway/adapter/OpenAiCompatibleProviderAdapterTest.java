package com.nageoffer.shortlink.aigateway.adapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
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

    @Test
    void shouldPassThroughUnknownOpenAiFields() {
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter();
        AiCanonicalChatRequest request = AiCanonicalChatRequest.builder()
                .providerModel("gpt-4o-mini")
                .messages(List.of(Map.of("role", "user", "content", "hello")))
                .stream(false)
                .extra(Map.of("top_p", 0.9, "seed", 7, "response_format", Map.of("type", "json_object")))
                .build();

        JSONObject payload = JSON.parseObject(JSON.toJSONString(adapter.toUpstreamRequest(request)));

        Assertions.assertEquals(0.9D, payload.getDoubleValue("top_p"));
        Assertions.assertEquals(7, payload.getIntValue("seed"));
        Assertions.assertEquals("json_object", payload.getJSONObject("response_format").getString("type"));
    }

    @Test
    void shouldForceIncludeUsageForStreamingRequests() {
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter();
        AiCanonicalChatRequest request = AiCanonicalChatRequest.builder()
                .providerModel("gpt-4o-mini")
                .messages(List.of(Map.of("role", "user", "content", "hello")))
                .stream(true)
                .build();

        JSONObject payload = JSON.parseObject(JSON.toJSONString(adapter.toUpstreamRequest(request)));

        // 关掉 usage 回传，流式链路就只能按预估值结算配额
        Assertions.assertTrue(payload.getJSONObject("stream_options").getBooleanValue("include_usage"));
    }

    @Test
    void shouldKeepExplicitFieldsOverExtraFields() {
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter();
        AiCanonicalChatRequest request = AiCanonicalChatRequest.builder()
                .providerModel("gpt-4o-mini")
                .messages(List.of(Map.of("role", "user", "content", "hello")))
                .stream(false)
                .maxTokens(64)
                .extra(Map.of("max_tokens", 999))
                .build();

        JSONObject payload = JSON.parseObject(JSON.toJSONString(adapter.toUpstreamRequest(request)));

        Assertions.assertEquals(64, payload.getIntValue("max_tokens"));
    }
}
