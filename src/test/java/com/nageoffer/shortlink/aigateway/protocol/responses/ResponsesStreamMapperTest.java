package com.nageoffer.shortlink.aigateway.protocol.responses;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

class ResponsesStreamMapperTest {

    private final ResponsesStreamMapper mapper = new ResponsesStreamMapper();

    @Test
    void shouldEmitResponsesEventSequenceWithIncreasingSequenceNumber() {
        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", 7);
        usage.put("completion_tokens", 3);
        List<ServerSentEvent<String>> events = mapper.toEventStream(Flux.just(
                textChunk("c1", "你", null, null),
                textChunk("c1", "好", null, null),
                textChunk("c1", null, "stop", usage),
                "[DONE]"), "gpt-4o-mini").collectList().block();

        Assertions.assertNotNull(events);
        Assertions.assertEquals(List.of(
                "response.created",
                "response.output_item.added",
                "response.content_part.added",
                "response.output_text.delta",
                "response.output_text.delta",
                "response.output_text.done",
                "response.content_part.done",
                "response.output_item.done",
                "response.completed"), events.stream().map(ServerSentEvent::event).toList());

        // 顺序号必须严格递增，客户端会据此排序与断点续传
        long previous = -1L;
        for (ServerSentEvent<String> event : events) {
            long sequence = JSON.parseObject(event.data()).getLongValue("sequence_number");
            Assertions.assertTrue(sequence > previous, "sequence_number 未递增: " + event.event());
            previous = sequence;
        }

        JSONObject completed = JSON.parseObject(events.get(8).data());
        JSONObject response = completed.getJSONObject("response");
        Assertions.assertEquals("completed", response.getString("status"));
        Assertions.assertEquals("你好", response.getString("output_text"));
        Assertions.assertEquals(7L, response.getJSONObject("usage").getLong("input_tokens"));
        Assertions.assertEquals(3L, response.getJSONObject("usage").getLong("output_tokens"));
        Assertions.assertEquals("gpt-4o-mini", response.getString("model"));
    }

    @Test
    void shouldEmitFunctionCallArgumentEvents() {
        List<ServerSentEvent<String>> events = mapper.toEventStream(Flux.just(
                toolCallChunk("c1", 0, "call_1", "get_weather", "{\"city\":"),
                toolCallChunk("c1", 0, null, null, "\"北京\"}"),
                textChunk("c1", null, "tool_calls", null),
                "[DONE]"), null).collectList().block();

        Assertions.assertNotNull(events);
        List<String> names = events.stream().map(ServerSentEvent::event).toList();
        Assertions.assertEquals(List.of(
                "response.created",
                "response.output_item.added",
                "response.function_call_arguments.delta",
                "response.function_call_arguments.delta",
                "response.function_call_arguments.done",
                "response.output_item.done",
                "response.completed"), names);

        JSONObject added = JSON.parseObject(events.get(1).data());
        Assertions.assertEquals("function_call", added.getJSONObject("item").getString("type"));
        Assertions.assertEquals("call_1", added.getJSONObject("item").getString("call_id"));

        JSONObject done = JSON.parseObject(events.get(4).data());
        Assertions.assertEquals("{\"city\":\"北京\"}", done.getString("arguments"));
    }

    @Test
    void shouldEmitErrorEventOnGatewayErrorFrame() {
        JSONObject error = new JSONObject();
        error.put("message", "上游挂了");
        error.put("code", "upstream_error");
        JSONObject frame = new JSONObject();
        frame.put("error", error);

        List<ServerSentEvent<String>> events = mapper.toEventStream(Flux.just(
                textChunk("c1", "部分", null, null),
                frame.toJSONString(),
                "[DONE]"), null).collectList().block();

        Assertions.assertNotNull(events);
        ServerSentEvent<String> last = events.get(events.size() - 1);
        Assertions.assertEquals("error", last.event());
        Assertions.assertEquals("上游挂了", JSON.parseObject(last.data()).getString("message"));
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
