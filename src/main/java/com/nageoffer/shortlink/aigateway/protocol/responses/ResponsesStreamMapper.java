package com.nageoffer.shortlink.aigateway.protocol.responses;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内部 OpenAI chunk 流 -> Responses API 事件流。
 * <p>
 * Responses 的流式事件带顺序号且类型繁多，这里实现客户端真正依赖的那条主线：
 * {@code response.created} → {@code output_item.added} → {@code content_part.added} →
 * {@code output_text.delta}* → {@code output_text.done} → {@code content_part.done} →
 * {@code output_item.done} → {@code response.completed}，
 * function_call 走 {@code function_call_arguments.delta/done}。
 * 事件名与字段名与官方文档一致，官方 SDK 可直接消费。
 */
@Slf4j
@Component
public class ResponsesStreamMapper {

    private static final String DONE_PAYLOAD = "[DONE]";

    public Flux<ServerSentEvent<String>> toEventStream(Flux<String> chunks, String clientModel) {
        return Flux.defer(() -> {
            StreamState state = new StreamState(clientModel);
            return chunks.concatMap(payload -> Flux.fromIterable(convert(payload, state)))
                    .concatWith(Flux.defer(() -> state.closed ? Flux.empty() : Flux.fromIterable(close(state))));
        });
    }

    private List<ServerSentEvent<String>> convert(String payload, StreamState state) {
        if (!StringUtils.hasText(payload)) {
            return List.of();
        }
        String trimmed = payload.trim();
        if (DONE_PAYLOAD.equals(trimmed)) {
            return close(state);
        }
        JSONObject chunk;
        try {
            chunk = JSON.parseObject(trimmed);
        } catch (Exception ex) {
            log.debug("跳过无法解析的流式分片: {}", trimmed);
            return List.of();
        }
        if (chunk == null) {
            return List.of();
        }

        JSONObject error = chunk.getJSONObject("error");
        if (error != null) {
            List<ServerSentEvent<String>> events = new ArrayList<>(close(state));
            JSONObject payload0 = new JSONObject();
            payload0.put("type", "error");
            payload0.put("code", error.getString("code") == null ? "upstream_error" : error.getString("code"));
            payload0.put("message", error.getString("message") == null ? "upstream error" : error.getString("message"));
            payload0.put("param", null);
            payload0.put("sequence_number", state.nextSequence());
            events.add(event("error", payload0));
            state.closed = true;
            return events;
        }

        List<ServerSentEvent<String>> events = new ArrayList<>(ensureCreated(state));
        if (chunk.getString("model") != null && !StringUtils.hasText(state.model)) {
            state.model = chunk.getString("model");
        }
        JSONObject usage = chunk.getJSONObject("usage");
        if (usage != null) {
            state.inputTokens = Math.max(state.inputTokens, longOrZero(usage.getLong("prompt_tokens")));
            state.outputTokens = Math.max(state.outputTokens, longOrZero(usage.getLong("completion_tokens")));
        }

        JSONArray choices = chunk.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return events;
        }
        JSONObject choice = choices.getJSONObject(0);
        if (choice == null) {
            return events;
        }
        JSONObject delta = choice.getJSONObject("delta");
        if (delta != null) {
            String text = delta.getString("content");
            if (StringUtils.hasText(text)) {
                events.addAll(appendTextDelta(state, text));
            }
            appendToolCallDeltas(events, state, delta.getJSONArray("tool_calls"));
        }
        if (choice.getString("finish_reason") != null) {
            events.addAll(close(state));
        }
        return events;
    }

    private List<ServerSentEvent<String>> ensureCreated(StreamState state) {
        if (state.created) {
            return List.of();
        }
        state.created = true;
        JSONObject response = baseResponse(state, "in_progress");
        JSONObject payload = new JSONObject();
        payload.put("type", "response.created");
        payload.put("sequence_number", state.nextSequence());
        payload.put("response", response);
        return List.of(event("response.created", payload));
    }

    private List<ServerSentEvent<String>> appendTextDelta(StreamState state, String text) {
        List<ServerSentEvent<String>> events = new ArrayList<>();
        if (!state.messageItemAdded) {
            state.messageItemAdded = true;
            JSONObject item = new JSONObject();
            item.put("id", state.messageItemId);
            item.put("type", "message");
            item.put("status", "in_progress");
            item.put("role", "assistant");
            item.put("content", new JSONArray());
            JSONObject payload = new JSONObject();
            payload.put("type", "response.output_item.added");
            payload.put("sequence_number", state.nextSequence());
            payload.put("output_index", state.messageOutputIndex());
            payload.put("item", item);
            events.add(event("response.output_item.added", payload));

            JSONObject part = new JSONObject();
            part.put("type", "output_text");
            part.put("text", "");
            part.put("annotations", new JSONArray());
            JSONObject partPayload = new JSONObject();
            partPayload.put("type", "response.content_part.added");
            partPayload.put("sequence_number", state.nextSequence());
            partPayload.put("item_id", state.messageItemId);
            partPayload.put("output_index", state.messageOutputIndex());
            partPayload.put("content_index", 0);
            partPayload.put("part", part);
            events.add(event("response.content_part.added", partPayload));
        }
        state.text.append(text);

        JSONObject payload = new JSONObject();
        payload.put("type", "response.output_text.delta");
        payload.put("sequence_number", state.nextSequence());
        payload.put("item_id", state.messageItemId);
        payload.put("output_index", state.messageOutputIndex());
        payload.put("content_index", 0);
        payload.put("delta", text);
        payload.put("logprobs", new JSONArray());
        events.add(event("response.output_text.delta", payload));
        return events;
    }

    private void appendToolCallDeltas(List<ServerSentEvent<String>> events, StreamState state, JSONArray toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        for (int index = 0; index < toolCalls.size(); index++) {
            JSONObject toolCall = toolCalls.getJSONObject(index);
            if (toolCall == null) {
                continue;
            }
            Integer toolIndex = toolCall.getInteger("index");
            int key = toolIndex == null ? index : toolIndex;
            StreamState.FunctionCall functionCall = state.functionCalls.get(key);
            if (functionCall == null) {
                JSONObject function = toolCall.getJSONObject("function");
                functionCall = new StreamState.FunctionCall(
                        "fc_" + UUID.randomUUID().toString().replace("-", ""),
                        state.nextOutputIndex(),
                        toolCall.getString("id"),
                        function == null ? null : function.getString("name"));
                state.functionCalls.put(key, functionCall);

                JSONObject item = new JSONObject();
                item.put("id", functionCall.itemId());
                item.put("type", "function_call");
                item.put("status", "in_progress");
                item.put("call_id", functionCall.callId());
                item.put("name", functionCall.name());
                item.put("arguments", "");
                JSONObject payload = new JSONObject();
                payload.put("type", "response.output_item.added");
                payload.put("sequence_number", state.nextSequence());
                payload.put("output_index", functionCall.outputIndex());
                payload.put("item", item);
                events.add(event("response.output_item.added", payload));
            }
            JSONObject function = toolCall.getJSONObject("function");
            String arguments = function == null ? null : function.getString("arguments");
            if (!StringUtils.hasText(arguments)) {
                continue;
            }
            functionCall.arguments().append(arguments);
            JSONObject payload = new JSONObject();
            payload.put("type", "response.function_call_arguments.delta");
            payload.put("sequence_number", state.nextSequence());
            payload.put("item_id", functionCall.itemId());
            payload.put("output_index", functionCall.outputIndex());
            payload.put("delta", arguments);
            events.add(event("response.function_call_arguments.delta", payload));
        }
    }

    private List<ServerSentEvent<String>> close(StreamState state) {
        if (state.closed) {
            return List.of();
        }
        List<ServerSentEvent<String>> events = new ArrayList<>(ensureCreated(state));
        JSONArray output = new JSONArray();

        if (state.messageItemAdded) {
            JSONObject part = new JSONObject();
            part.put("type", "output_text");
            part.put("text", state.text.toString());
            part.put("annotations", new JSONArray());

            JSONObject textDone = new JSONObject();
            textDone.put("type", "response.output_text.done");
            textDone.put("sequence_number", state.nextSequence());
            textDone.put("item_id", state.messageItemId);
            textDone.put("output_index", state.messageOutputIndex());
            textDone.put("content_index", 0);
            textDone.put("text", state.text.toString());
            events.add(event("response.output_text.done", textDone));

            JSONObject partDone = new JSONObject();
            partDone.put("type", "response.content_part.done");
            partDone.put("sequence_number", state.nextSequence());
            partDone.put("item_id", state.messageItemId);
            partDone.put("output_index", state.messageOutputIndex());
            partDone.put("content_index", 0);
            partDone.put("part", part);
            events.add(event("response.content_part.done", partDone));

            JSONObject item = new JSONObject();
            item.put("id", state.messageItemId);
            item.put("type", "message");
            item.put("status", "completed");
            item.put("role", "assistant");
            JSONArray content = new JSONArray();
            content.add(part);
            item.put("content", content);
            JSONObject itemDone = new JSONObject();
            itemDone.put("type", "response.output_item.done");
            itemDone.put("sequence_number", state.nextSequence());
            itemDone.put("output_index", state.messageOutputIndex());
            itemDone.put("item", item);
            events.add(event("response.output_item.done", itemDone));

            JSONObject outputItem = new JSONObject();
            outputItem.put("id", state.messageItemId);
            outputItem.put("type", "message");
            outputItem.put("status", "completed");
            outputItem.put("role", "assistant");
            outputItem.put("content", content);
            output.add(outputItem);
        }

        state.functionCalls.values().forEach(functionCall -> {
            JSONObject item = new JSONObject();
            item.put("id", functionCall.itemId());
            item.put("type", "function_call");
            item.put("status", "completed");
            item.put("call_id", functionCall.callId());
            item.put("name", functionCall.name());
            item.put("arguments", functionCall.arguments().toString());

            JSONObject argumentsDone = new JSONObject();
            argumentsDone.put("type", "response.function_call_arguments.done");
            argumentsDone.put("sequence_number", state.nextSequence());
            argumentsDone.put("item_id", functionCall.itemId());
            argumentsDone.put("output_index", functionCall.outputIndex());
            argumentsDone.put("arguments", functionCall.arguments().toString());
            events.add(event("response.function_call_arguments.done", argumentsDone));

            JSONObject itemDone = new JSONObject();
            itemDone.put("type", "response.output_item.done");
            itemDone.put("sequence_number", state.nextSequence());
            itemDone.put("output_index", functionCall.outputIndex());
            itemDone.put("item", item);
            events.add(event("response.output_item.done", itemDone));

            output.add(item);
        });

        JSONObject response = baseResponse(state, "completed");
        response.put("output", output);
        response.put("output_text", state.text.toString());
        JSONObject usage = new JSONObject();
        usage.put("input_tokens", state.inputTokens);
        usage.put("output_tokens", state.outputTokens);
        usage.put("total_tokens", state.inputTokens + state.outputTokens);
        response.put("usage", usage);

        JSONObject payload = new JSONObject();
        payload.put("type", "response.completed");
        payload.put("sequence_number", state.nextSequence());
        payload.put("response", response);
        events.add(event("response.completed", payload));

        state.closed = true;
        return events;
    }

    private JSONObject baseResponse(StreamState state, String status) {
        JSONObject response = new JSONObject();
        response.put("id", state.responseId);
        response.put("object", "response");
        response.put("created_at", state.createdAt);
        response.put("status", status);
        response.put("model", state.resolvedModel());
        response.put("output", new JSONArray());
        response.put("output_text", "");
        response.put("error", null);
        response.put("incomplete_details", null);
        return response;
    }

    private ServerSentEvent<String> event(String name, JSONObject payload) {
        return ServerSentEvent.<String>builder()
                .event(name)
                .data(JSON.toJSONString(payload, JSONWriter.Feature.WriteNulls))
                .build();
    }

    private long longOrZero(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 单次订阅内的流式状态。
     */
    private static final class StreamState {

        private final String clientModel;

        private final String responseId = "resp_" + UUID.randomUUID().toString().replace("-", "");

        private final String messageItemId = "msg_" + UUID.randomUUID().toString().replace("-", "");

        private final long createdAt = Instant.now().getEpochSecond();

        private final StringBuilder text = new StringBuilder();

        private final Map<Integer, FunctionCall> functionCalls = new LinkedHashMap<>();

        private String model;

        private boolean created;

        private boolean closed;

        private boolean messageItemAdded;

        private int sequence;

        private int outputCursor = -1;

        private long inputTokens;

        private long outputTokens;

        private StreamState(String clientModel) {
            this.clientModel = clientModel;
        }

        private int nextSequence() {
            return sequence++;
        }

        /**
         * 文本消息固定占 0 号输出位，之后分配给逐个 function_call。
         */
        private int messageOutputIndex() {
            return 0;
        }

        private int nextOutputIndex() {
            outputCursor = Math.max(outputCursor, 0) + 1;
            return outputCursor;
        }

        private String resolvedModel() {
            if (StringUtils.hasText(clientModel)) {
                return clientModel;
            }
            return StringUtils.hasText(model) ? model : "unknown";
        }

        private record FunctionCall(String itemId, int outputIndex, String callId, String name, StringBuilder arguments) {

            private FunctionCall(String itemId, int outputIndex, String callId, String name) {
                this(itemId, outputIndex, callId, name, new StringBuilder());
            }
        }
    }
}
