package com.nageoffer.shortlink.aigateway.protocol.anthropic;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Anthropic 事件流的顺序约束。用对象构造分片而不是手写 JSON 字符串，
 * 免得为了一层转义把测试本身写错。
 */
class AnthropicStreamMapperTest {

    private final AnthropicStreamMapper mapper = new AnthropicStreamMapper();

    @Test
    void shouldEmitAnthropicEventSequenceForTextStream() {
        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", 5);
        usage.put("completion_tokens", 2);
        List<ServerSentEvent<String>> events = mapper.toEventStream(Flux.just(
                textChunk("chatcmpl-1", "你", null, null),
                textChunk("chatcmpl-1", "好", null, null),
                textChunk("chatcmpl-1", null, "stop", usage),
                "[DONE]"), "claude-compatible").collectList().block();

        Assertions.assertNotNull(events);
        Assertions.assertEquals(List.of(
                "message_start",
                "content_block_start",
                "content_block_delta",
                "content_block_delta",
                "content_block_stop",
                "message_delta",
                "message_stop"), events.stream().map(ServerSentEvent::event).toList());

        JSONObject messageStart = JSON.parseObject(events.get(0).data());
        JSONObject message = messageStart.getJSONObject("message");
        Assertions.assertEquals("claude-compatible", message.getString("model"));
        Assertions.assertEquals("message", message.getString("type"));
        Assertions.assertTrue(message.getJSONArray("content").isEmpty());

        JSONObject textDelta = JSON.parseObject(events.get(2).data());
        Assertions.assertEquals("text_delta", textDelta.getJSONObject("delta").getString("type"));
        Assertions.assertEquals("你", textDelta.getJSONObject("delta").getString("text"));

        JSONObject messageDelta = JSON.parseObject(events.get(5).data());
        Assertions.assertEquals("end_turn", messageDelta.getJSONObject("delta").getString("stop_reason"));
        Assertions.assertEquals(2L, messageDelta.getJSONObject("usage").getLong("output_tokens"));
    }

    @Test
    void shouldEmitToolUseBlockWithInputJsonDelta() {
        List<ServerSentEvent<String>> events = mapper.toEventStream(Flux.just(
                textChunk("c1", "查", null, null),
                toolCallChunk("c1", 0, "call_1", "get_weather", "{\"city\":"),
                toolCallChunk("c1", 0, null, null, "\"北京\"}"),
                textChunk("c1", null, "tool_calls", null),
                "[DONE]"), null).collectList().block();

        Assertions.assertNotNull(events);
        Assertions.assertEquals(List.of(
                "message_start",
                "content_block_start",
                "content_block_delta",
                "content_block_start",
                "content_block_delta",
                "content_block_delta",
                "content_block_stop",
                "content_block_stop",
                "message_delta",
                "message_stop"), events.stream().map(ServerSentEvent::event).toList());

        JSONObject toolBlockStart = JSON.parseObject(events.get(3).data());
        Assertions.assertEquals(1, toolBlockStart.getInteger("index"));
        JSONObject block = toolBlockStart.getJSONObject("content_block");
        Assertions.assertEquals("tool_use", block.getString("type"));
        Assertions.assertEquals("call_1", block.getString("id"));
        Assertions.assertEquals("get_weather", block.getString("name"));

        JSONObject partial = JSON.parseObject(events.get(4).data());
        Assertions.assertEquals("input_json_delta", partial.getJSONObject("delta").getString("type"));
        Assertions.assertEquals("{\"city\":", partial.getJSONObject("delta").getString("partial_json"));

        JSONObject messageDelta = JSON.parseObject(events.get(8).data());
        Assertions.assertEquals("tool_use", messageDelta.getJSONObject("delta").getString("stop_reason"));
    }

    @Test
    void shouldConvertGatewayErrorFrameToAnthropicErrorEvent() {
        JSONObject error = new JSONObject();
        error.put("message", "上游挂了");
        error.put("type", "api_error");
        error.put("code", "upstream_error");
        JSONObject errorFrame = new JSONObject();
        errorFrame.put("error", error);

        List<ServerSentEvent<String>> events = mapper.toEventStream(Flux.just(
                textChunk("c1", "部分", null, null),
                errorFrame.toJSONString(),
                "[DONE]"), null).collectList().block();

        Assertions.assertNotNull(events);
        // 错误帧也要按协议收尾，且随后的 [DONE] 不会造成二次收尾
        Assertions.assertEquals(List.of("message_start", "content_block_start", "content_block_delta",
                "content_block_stop", "message_delta", "message_stop", "error"),
                events.stream().map(ServerSentEvent::event).toList());

        JSONObject payload = JSON.parseObject(events.get(6).data());
        Assertions.assertEquals("error", payload.getString("type"));
        Assertions.assertEquals("上游挂了", payload.getJSONObject("error").getString("message"));
    }

    @Test
    void shouldCloseStreamWhenUpstreamEndsWithoutFinishReason() {
        List<ServerSentEvent<String>> events = mapper.toEventStream(
                Flux.just(textChunk("c1", "hi", null, null)), null).collectList().block();

        Assertions.assertNotNull(events);
        Assertions.assertEquals("message_stop", events.get(events.size() - 1).event());
    }

    private String textChunk(String id, String content, String finishReason, JSONObject usage) {
        JSONObject delta = new JSONObject();
        if (content != null) {
            delta.put("content", content);
        }
        return chunk(id, delta, finishReason, usage);
    }

    private String toolCallChunk(String id, int index, String callId, String name, String arguments) {
        JSONObject function = new JSONObject();
        if (name != null) {
            function.put("name", name);
        }
        if (arguments != null) {
            function.put("arguments", arguments);
        }
        JSONObject toolCall = new JSONObject();
        toolCall.put("index", index);
        if (callId != null) {
            toolCall.put("id", callId);
        }
        toolCall.put("function", function);

        JSONArray toolCalls = new JSONArray();
        toolCalls.add(toolCall);
        JSONObject delta = new JSONObject();
        delta.put("tool_calls", toolCalls);
        return chunk(id, delta, null, null);
    }

    private String chunk(String id, JSONObject delta, String finishReason, JSONObject usage) {
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        JSONArray choices = new JSONArray();
        choices.add(choice);
        JSONObject chunk = new JSONObject();
        chunk.put("id", id);
        chunk.put("choices", choices);
        if (usage != null) {
            chunk.put("usage", usage);
        }
        return chunk.toJSONString();
    }
}
