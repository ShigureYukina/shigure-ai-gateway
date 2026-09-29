package com.nageoffer.shortlink.aigateway.protocol.anthropic;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内部 OpenAI chunk 流 -> Anthropic Messages 事件流。
 * <p>
 * Anthropic 的流式不是"一串 delta"，而是有严格顺序的事件机：
 * {@code message_start} → 每个内容块的 {@code content_block_start/delta/stop} →
 * {@code message_delta} → {@code message_stop}。少发或多发都会让官方 SDK 解析失败，
 * 所以这里用状态机保证顺序，并保证无论上游怎么结束（finish_reason、[DONE]、错误帧、正常断流）
 * 都只收尾一次。
 * <p>
 * 事件载荷构造与 stop_reason 映射统一走 {@link AnthropicProtocolCodec}，与出口方向共用同一份映射表。
 */
@Slf4j
@Component
public class AnthropicStreamMapper {

    public Flux<ServerSentEvent<String>> toEventStream(Flux<String> chunks, String clientModel) {
        return Flux.defer(() -> {
            StreamState state = new StreamState(clientModel);
            return chunks.concatMap(payload -> Flux.fromIterable(convert(payload, state)))
                    .concatWith(Flux.defer(() -> state.closed ? Flux.empty() : Flux.fromIterable(close(state, null))));
        });
    }

    private List<ServerSentEvent<String>> convert(String payload, StreamState state) {
        if (!StringUtils.hasText(payload)) {
            return List.of();
        }
        String trimmed = payload.trim();
        if (AnthropicProtocolCodec.DONE_PAYLOAD.equals(trimmed)) {
            return close(state, null);
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
            List<ServerSentEvent<String>> events = new ArrayList<>(close(state, null));
            events.add(AnthropicProtocolCodec.sseEvent("error", AnthropicProtocolCodec.anthropicError(error)));
            state.closed = true;
            return events;
        }

        List<ServerSentEvent<String>> events = new ArrayList<>();
        if (chunk.getString("id") != null) {
            state.messageId = chunk.getString("id");
        }
        if (chunk.getString("model") != null && !StringUtils.hasText(state.model)) {
            state.model = chunk.getString("model");
        }
        if (!state.started) {
            events.add(messageStart(state));
            state.started = true;
        }

        JSONObject usage = chunk.getJSONObject("usage");
        if (usage != null) {
            state.inputTokens = Math.max(state.inputTokens,
                    AnthropicProtocolCodec.longOrZero(usage.getLong("prompt_tokens")));
            state.outputTokens = Math.max(state.outputTokens,
                    AnthropicProtocolCodec.longOrZero(usage.getLong("completion_tokens")));
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
                appendTextDelta(events, state, text);
            }
            appendToolCallDeltas(events, state, delta.getJSONArray(AnthropicProtocolCodec.FIELD_TOOL_CALLS));
        }
        String finishReason = choice.getString("finish_reason");
        if (finishReason != null) {
            state.stopReason = AnthropicProtocolCodec.toAnthropicStopReason(
                    finishReason, !state.toolBlockIndexes.isEmpty());
            events.addAll(close(state, null));
        }
        return events;
    }

    private void appendTextDelta(List<ServerSentEvent<String>> events, StreamState state, String text) {
        if (state.textBlockIndex == null) {
            int index = state.nextBlockIndex++;
            state.textBlockIndex = index;
            state.openBlocks.add(index);
            events.add(AnthropicProtocolCodec.sseEvent("content_block_start",
                    AnthropicProtocolCodec.anthropicBlockStart(index, AnthropicProtocolCodec.toAnthropicTextBlock(""))));
        }
        events.add(AnthropicProtocolCodec.sseEvent("content_block_delta",
                AnthropicProtocolCodec.anthropicTextDelta(state.textBlockIndex, text)));
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
            Integer blockIndex = state.toolBlockIndexes.get(key);
            if (blockIndex == null) {
                blockIndex = state.nextBlockIndex++;
                state.toolBlockIndexes.put(key, blockIndex);
                state.openBlocks.add(blockIndex);
                JSONObject function = toolCall.getJSONObject("function");
                events.add(AnthropicProtocolCodec.sseEvent("content_block_start",
                        AnthropicProtocolCodec.anthropicBlockStart(blockIndex,
                                AnthropicProtocolCodec.toAnthropicToolUseBlock(
                                        toolCall.getString("id"),
                                        function == null ? null : function.getString("name"),
                                        new JSONObject()))));
            }
            JSONObject function = toolCall.getJSONObject("function");
            String arguments = function == null ? null : function.getString("arguments");
            if (StringUtils.hasText(arguments)) {
                events.add(AnthropicProtocolCodec.sseEvent("content_block_delta",
                        AnthropicProtocolCodec.anthropicInputJsonDelta(blockIndex, arguments)));
            }
        }
    }

    private List<ServerSentEvent<String>> close(StreamState state, String overrideStopReason) {
        if (state.closed) {
            return List.of();
        }
        List<ServerSentEvent<String>> events = new ArrayList<>();
        if (!state.started) {
            events.add(messageStart(state));
            state.started = true;
        }
        for (Integer index : state.openBlocks) {
            events.add(AnthropicProtocolCodec.sseEvent("content_block_stop",
                    AnthropicProtocolCodec.anthropicBlockStop(index)));
        }
        state.openBlocks.clear();

        String stopReason = overrideStopReason != null
                ? overrideStopReason
                : (state.stopReason == null ? "end_turn" : state.stopReason);
        events.add(AnthropicProtocolCodec.sseEvent("message_delta",
                AnthropicProtocolCodec.anthropicMessageDelta(stopReason, state.outputTokens)));
        events.add(AnthropicProtocolCodec.sseEvent("message_stop",
                AnthropicProtocolCodec.anthropicEvent("message_stop")));
        state.closed = true;
        return events;
    }

    private ServerSentEvent<String> messageStart(StreamState state) {
        JSONObject usage = new JSONObject();
        usage.put("input_tokens", state.inputTokens);
        usage.put("output_tokens", state.outputTokens);
        JSONObject message = new JSONObject();
        message.put("id", state.messageId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", state.resolvedModel());
        message.put("content", new JSONArray());
        message.put("stop_reason", null);
        message.put("stop_sequence", null);
        message.put("usage", usage);

        JSONObject payload = new JSONObject();
        payload.put("type", "message_start");
        payload.put("message", message);
        return AnthropicProtocolCodec.sseEvent("message_start", payload);
    }

    /**
     * 单次订阅内的流式状态：Anthropic 的事件顺序依赖它，必须按订阅隔离。
     */
    private static final class StreamState {

        private final String clientModel;

        private String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");

        private String model;

        private boolean started;

        private boolean closed;

        private int nextBlockIndex;

        private final List<Integer> openBlocks = new ArrayList<>();

        private final Map<Integer, Integer> toolBlockIndexes = new LinkedHashMap<>();

        private Integer textBlockIndex;

        private String stopReason;

        private long inputTokens;

        private long outputTokens;

        private StreamState(String clientModel) {
            this.clientModel = clientModel;
        }

        private String resolvedModel() {
            return AnthropicProtocolCodec.resolveModel(clientModel, model);
        }
    }
}
