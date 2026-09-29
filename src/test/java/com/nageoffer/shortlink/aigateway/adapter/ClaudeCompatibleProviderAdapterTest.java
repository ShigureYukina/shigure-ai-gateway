package com.nageoffer.shortlink.aigateway.adapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nageoffer.shortlink.aigateway.dto.model.AiCanonicalChatRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

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

    @Test
    void shouldMapClaudeUsageToOpenAiUsage() {
        ClaudeCompatibleProviderAdapter adapter = new ClaudeCompatibleProviderAdapter();
        AiCanonicalChatRequest request = request(false);

        String upstream = """
                {"id":"msg_1","content":[{"type":"text","text":"hi"}],
                 "stop_reason":"end_turn",
                 "usage":{"input_tokens":11,"output_tokens":7}}
                """;
        JSONObject normalized = JSON.parseObject(adapter.fromUpstreamResponse(upstream, request).block());

        JSONObject usage = normalized.getJSONObject("usage");
        Assertions.assertEquals(11L, usage.getLong("prompt_tokens"));
        Assertions.assertEquals(7L, usage.getLong("completion_tokens"));
        Assertions.assertEquals(18L, usage.getLong("total_tokens"));
        Assertions.assertEquals("stop", normalized.getJSONArray("choices").getJSONObject(0).getString("finish_reason"));
    }

    @Test
    void shouldSplitSystemMessageAndConvertTools() {
        ClaudeCompatibleProviderAdapter adapter = new ClaudeCompatibleProviderAdapter();
        AiCanonicalChatRequest request = AiCanonicalChatRequest.builder()
                .clientModel("claude-3-5-sonnet-compatible")
                .providerModel("claude-3-5-sonnet-latest")
                .stream(false)
                .maxTokens(64)
                .messages(List.of(
                        Map.<String, Object>of("role", "system", "content", "be brief"),
                        Map.<String, Object>of("role", "user", "content", "weather?")))
                .extra(Map.of(
                        "tools", List.of(Map.of(
                                "type", "function",
                                "function", Map.of(
                                        "name", "get_weather",
                                        "description", "lookup",
                                        "parameters", Map.of("type", "object")))),
                        "tool_choice", "auto",
                        "response_format", Map.of("type", "json_object")))
                .build();

        JSONObject payload = JSON.parseObject(JSON.toJSONString(adapter.toUpstreamRequest(request)));

        Assertions.assertEquals("be brief", payload.getString("system"));
        Assertions.assertEquals(64, payload.getIntValue("max_tokens"));
        Assertions.assertEquals(1, payload.getJSONArray("messages").size());
        JSONObject tool = payload.getJSONArray("tools").getJSONObject(0);
        Assertions.assertEquals("get_weather", tool.getString("name"));
        Assertions.assertNotNull(tool.getJSONObject("input_schema"));
        Assertions.assertEquals("auto", payload.getJSONObject("tool_choice").getString("type"));
        // 上游不认识 OpenAI 专有字段，必须被裁剪掉
        Assertions.assertFalse(payload.containsKey("response_format"));
    }

    @Test
    void shouldConvertAnthropicEventsToOpenAiChunks() {
        ClaudeCompatibleProviderAdapter adapter = new ClaudeCompatibleProviderAdapter();
        Flux<String> events = Flux.just(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-3-5-sonnet-latest\",\"usage\":{\"input_tokens\":9}}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}",
                "{\"type\":\"message_stop\"}");

        List<String> chunks = adapter.fromUpstreamSse(events, request(true)).collectList().block();

        Assertions.assertNotNull(chunks);
        Assertions.assertEquals(4, chunks.size());
        JSONObject roleChunk = JSON.parseObject(chunks.get(0));
        Assertions.assertEquals("assistant", roleChunk.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("delta").getString("role"));
        JSONObject textChunk = JSON.parseObject(chunks.get(1));
        Assertions.assertEquals("hello", textChunk.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("delta").getString("content"));
        JSONObject finalChunk = JSON.parseObject(chunks.get(2));
        Assertions.assertEquals("stop", finalChunk.getJSONArray("choices").getJSONObject(0).getString("finish_reason"));
        Assertions.assertEquals(12L, finalChunk.getJSONObject("usage").getLong("total_tokens"));
        Assertions.assertEquals("[DONE]", chunks.get(3));
    }

    private AiCanonicalChatRequest request(boolean stream) {
        return AiCanonicalChatRequest.builder()
                .clientModel("claude-3-5-sonnet-compatible")
                .providerModel("claude-3-5-sonnet-latest")
                .messages(List.of(Map.<String, Object>of("role", "user", "content", "hello")))
                .stream(stream)
                .build();
    }
}
