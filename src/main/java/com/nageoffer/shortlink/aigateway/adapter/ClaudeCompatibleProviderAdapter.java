package com.nageoffer.shortlink.aigateway.adapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nageoffer.shortlink.aigateway.dto.model.AiCanonicalChatRequest;
import com.nageoffer.shortlink.aigateway.governance.ContentTextExtractor;
import com.nageoffer.shortlink.aigateway.protocol.anthropic.AnthropicProtocolCodec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude 上游适配器。
 * <p>
 * 三段转换必须齐全，缺任何一段都会导致"看起来支持、实际跑不通"：
 * <ul>
 *   <li>请求：OpenAI 字段 -> Messages API 字段（system 拆分、tools 结构、tool 结果回填）；</li>
 *   <li>响应：content blocks -> OpenAI choices，并把 input/output_tokens 映射为 usage；</li>
 *   <li>流式：Anthropic 事件 -> OpenAI chunk，缺这一步客户端拿到的是无法解析的事件流。</li>
 * </ul>
 * 上游路径由 {@code provider-chat-path} 配置，默认 {@code /v1/messages}。
 * <p>
 * 字段级映射（tools/tool_choice/usage/stop_reason 等）统一走
 * {@link AnthropicProtocolCodec}，与入口方向的 {@code AnthropicMessagesMapper} 共用同一份映射表。
 */
@Component
public class ClaudeCompatibleProviderAdapter implements ProviderAdapter {

    /**
     * Anthropic Messages API 要求 max_tokens 必填，客户端未给时给一个保守默认值。
     */
    private static final int DEFAULT_MAX_TOKENS = 1024;

    @Override
    public String providerName() {
        return "claude";
    }

    @Override
    public Object toUpstreamRequest(AiCanonicalChatRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.getProviderModel());
        payload.put("max_tokens", request.getMaxTokens() == null ? DEFAULT_MAX_TOKENS : request.getMaxTokens());
        payload.put("stream", Boolean.TRUE.equals(request.getStream()));
        if (request.getTemperature() != null) {
            payload.put("temperature", request.getTemperature());
        }

        SplitMessages split = splitMessages(request.getMessages());
        if (!split.systemBlocks().isEmpty()) {
            payload.put("system", String.join("\n\n", split.systemBlocks()));
        }
        payload.put("messages", split.messages());

        AnthropicProtocolCodec.applyOpenAiExtraToAnthropicPayload(payload, request.getExtra());
        return payload;
    }

    @Override
    public Mono<String> fromUpstreamResponse(String upstreamBody, AiCanonicalChatRequest request) {
        JSONObject upstream = JSON.parseObject(upstreamBody);
        JSONObject message = new JSONObject();
        message.put("role", "assistant");

        StringBuilder text = new StringBuilder();
        JSONArray toolCalls = new JSONArray();
        JSONArray contentBlocks = upstream.getJSONArray("content");
        if (contentBlocks != null) {
            for (int index = 0; index < contentBlocks.size(); index++) {
                JSONObject block = contentBlocks.getJSONObject(index);
                if (block == null) {
                    continue;
                }
                String blockType = block.getString("type");
                if (block.getString("text") != null
                        && (blockType == null || AnthropicProtocolCodec.BLOCK_TEXT.equals(blockType))) {
                    text.append(block.getString("text"));
                } else if (AnthropicProtocolCodec.BLOCK_TOOL_USE.equals(blockType)) {
                    toolCalls.add(AnthropicProtocolCodec.toOpenAiToolCall(
                            block.getString("id"), block.getString("name"), block.get("input")));
                }
            }
        }
        message.put("content", text.isEmpty() ? null : text.toString());
        if (!toolCalls.isEmpty()) {
            message.put(AnthropicProtocolCodec.FIELD_TOOL_CALLS, toolCalls);
        }

        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason",
                AnthropicProtocolCodec.toOpenAiFinishReason(upstream.getString("stop_reason"), !toolCalls.isEmpty()));

        JSONArray choices = new JSONArray();
        choices.add(choice);

        JSONObject normalized = new JSONObject();
        normalized.put("id", upstream.getString("id"));
        normalized.put("object", "chat.completion");
        normalized.put("created", Instant.now().getEpochSecond());
        normalized.put("model", AnthropicProtocolCodec.resolveModel(
                request == null ? null : request.getClientModel(), upstream.getString("model")));
        normalized.put("choices", choices);
        JSONObject usage = AnthropicProtocolCodec.toOpenAiUsage(upstream.getJSONObject("usage"));
        if (usage != null) {
            normalized.put("usage", usage);
        }
        return Mono.just(normalized.toJSONString());
    }

    /**
     * Anthropic 事件流 -> OpenAI chunk 流。
     * <p>
     * 状态（消息 id、用量、是否已补 [DONE]）按订阅创建，避免多个并发订阅互相污染。
     */
    @Override
    public Flux<String> fromUpstreamSse(Flux<String> upstreamFlux, AiCanonicalChatRequest request) {
        return Flux.defer(() -> {
            StreamState state = new StreamState(request);
            return upstreamFlux.concatMap(payload -> Flux.fromIterable(convertEvent(payload, state)))
                    .concatWith(Flux.defer(() -> state.doneEmitted
                            ? Flux.empty()
                            : Flux.just(AnthropicProtocolCodec.DONE_PAYLOAD)));
        });
    }

    private List<String> convertEvent(String payload, StreamState state) {
        if (!StringUtils.hasText(payload)) {
            return List.of();
        }
        if (AnthropicProtocolCodec.DONE_PAYLOAD.equals(payload.trim())) {
            state.doneEmitted = true;
            return List.of(AnthropicProtocolCodec.DONE_PAYLOAD);
        }
        JSONObject event = JSON.parseObject(payload);
        String eventType = event.getString("type");
        if (eventType == null) {
            return List.of();
        }
        return switch (eventType) {
            case "message_start" -> handleMessageStart(event, state);
            case "content_block_start" -> handleContentBlockStart(event, state);
            case "content_block_delta" -> handleContentBlockDelta(event, state);
            case "message_delta" -> handleMessageDelta(event, state);
            case "message_stop" -> {
                state.doneEmitted = true;
                yield List.of(AnthropicProtocolCodec.DONE_PAYLOAD);
            }
            default -> List.of();
        };
    }

    private List<String> handleMessageStart(JSONObject event, StreamState state) {
        JSONObject message = event.getJSONObject("message");
        if (message != null) {
            state.messageId = message.getString("id");
            state.resolvedModel = message.getString("model");
            JSONObject usage = AnthropicProtocolCodec.toOpenAiUsage(message.getJSONObject("usage"));
            if (usage != null) {
                state.usage = usage;
            }
        }
        JSONObject delta = new JSONObject();
        delta.put("role", "assistant");
        delta.put("content", "");
        return List.of(buildChunk(state, delta, null, null));
    }

    private List<String> handleContentBlockStart(JSONObject event, StreamState state) {
        JSONObject block = event.getJSONObject("content_block");
        if (block == null || !AnthropicProtocolCodec.BLOCK_TOOL_USE.equals(block.getString("type"))) {
            return List.of();
        }
        Integer blockIndex = event.getInteger("index");
        state.toolCallIndex = blockIndex == null ? 0 : blockIndex;
        Map<String, Object> toolCall = AnthropicProtocolCodec.toOpenAiStreamingToolCall(
                state.toolCallIndex, block.getString("id"), block.getString("name"));

        JSONArray toolCalls = new JSONArray();
        toolCalls.add(toolCall);
        JSONObject delta = new JSONObject();
        delta.put(AnthropicProtocolCodec.FIELD_TOOL_CALLS, toolCalls);
        return List.of(buildChunk(state, delta, null, null));
    }

    private List<String> handleContentBlockDelta(JSONObject event, StreamState state) {
        JSONObject deltaPayload = event.getJSONObject("delta");
        if (deltaPayload == null) {
            return List.of();
        }
        String deltaType = deltaPayload.getString("type");
        JSONObject delta = new JSONObject();
        if ("text_delta".equals(deltaType)) {
            delta.put("content", deltaPayload.getString("text"));
        } else if ("input_json_delta".equals(deltaType)) {
            JSONObject function = new JSONObject();
            function.put("arguments", deltaPayload.getString("partial_json"));
            JSONObject toolCall = new JSONObject();
            toolCall.put("index", state.toolCallIndex);
            toolCall.put("function", function);
            JSONArray toolCalls = new JSONArray();
            toolCalls.add(toolCall);
            delta.put(AnthropicProtocolCodec.FIELD_TOOL_CALLS, toolCalls);
        } else {
            return List.of();
        }
        return List.of(buildChunk(state, delta, null, null));
    }

    private List<String> handleMessageDelta(JSONObject event, StreamState state) {
        JSONObject deltaPayload = event.getJSONObject("delta");
        String stopReason = deltaPayload == null ? null : deltaPayload.getString("stop_reason");
        JSONObject usage = AnthropicProtocolCodec.toOpenAiUsage(event.getJSONObject("usage"));
        if (usage != null) {
            state.usage = AnthropicProtocolCodec.mergeOpenAiUsage(state.usage, usage);
        }
        return List.of(buildChunk(state, new JSONObject(),
                AnthropicProtocolCodec.toOpenAiFinishReason(stopReason, state.toolCallIndex != null), state.usage));
    }

    private String buildChunk(StreamState state, JSONObject delta, String finishReason, JSONObject usage) {
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);

        JSONArray choices = new JSONArray();
        choices.add(choice);

        JSONObject chunk = new JSONObject();
        chunk.put("id", state.messageId);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", state.createdSeconds);
        chunk.put("model", AnthropicProtocolCodec.resolveModel(
                state.request == null ? null : state.request.getClientModel(), state.resolvedModel));
        chunk.put("choices", choices);
        if (usage != null) {
            chunk.put("usage", usage);
        }
        return chunk.toJSONString();
    }

    private SplitMessages splitMessages(List<Map<String, Object>> messages) {
        List<String> systemBlocks = new ArrayList<>();
        List<Map<String, Object>> converted = new ArrayList<>();
        if (messages == null) {
            return new SplitMessages(systemBlocks, converted);
        }
        for (Map<String, Object> message : messages) {
            if (message == null) {
                continue;
            }
            String role = message.get("role") == null ? "" : String.valueOf(message.get("role"));
            if ("system".equalsIgnoreCase(role)) {
                String text = ContentTextExtractor.text(message.get("content"));
                if (StringUtils.hasText(text)) {
                    systemBlocks.add(text);
                }
                continue;
            }
            if ("tool".equalsIgnoreCase(role)) {
                converted.add(toolResultMessage(message));
                continue;
            }
            if ("assistant".equalsIgnoreCase(role)
                    && message.get(AnthropicProtocolCodec.FIELD_TOOL_CALLS) instanceof List<?> toolCalls
                    && !toolCalls.isEmpty()) {
                converted.add(assistantToolCallMessage(message, toolCalls));
                continue;
            }
            Map<String, Object> simple = new LinkedHashMap<>();
            simple.put("role", role);
            simple.put("content", message.get("content"));
            converted.add(simple);
        }
        return new SplitMessages(systemBlocks, converted);
    }

    /**
     * OpenAI 的 {@code tool} 消息在 Anthropic 侧是 user 消息里的 tool_result 块。
     */
    private Map<String, Object> toolResultMessage(Map<String, Object> message) {
        JSONArray blocks = new JSONArray();
        blocks.add(AnthropicProtocolCodec.toAnthropicToolResultBlock(
                message.get(AnthropicProtocolCodec.FIELD_TOOL_CALL_ID),
                ContentTextExtractor.text(message.get("content"))));
        Map<String, Object> converted = new LinkedHashMap<>();
        converted.put("role", "user");
        converted.put("content", blocks);
        return converted;
    }

    private Map<String, Object> assistantToolCallMessage(Map<String, Object> message, List<?> toolCalls) {
        JSONArray blocks = new JSONArray();
        String text = ContentTextExtractor.text(message.get("content"));
        if (StringUtils.hasText(text)) {
            Map<String, Object> textBlock = new LinkedHashMap<>();
            textBlock.put("type", AnthropicProtocolCodec.BLOCK_TEXT);
            textBlock.put("text", text);
            blocks.add(textBlock);
        }
        for (Object each : toolCalls) {
            if (!(each instanceof Map<?, ?> toolCall)) {
                continue;
            }
            Map<?, ?> function = toolCall.get("function") instanceof Map<?, ?> functionMap ? functionMap : Map.of();
            blocks.add(AnthropicProtocolCodec.toAnthropicToolUseBlock(
                    toolCall.get("id"),
                    function.get("name"),
                    AnthropicProtocolCodec.parseArguments(function.get("arguments"))));
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        converted.put("role", "assistant");
        converted.put("content", blocks);
        return converted;
    }

    private record SplitMessages(List<String> systemBlocks, List<Map<String, Object>> messages) {
    }

    /**
     * 单次订阅内的流式转换状态。
     */
    private static final class StreamState {

        private final AiCanonicalChatRequest request;

        private final long createdSeconds = Instant.now().getEpochSecond();

        private String messageId;

        private String resolvedModel;

        private JSONObject usage;

        private Integer toolCallIndex;

        private boolean doneEmitted;

        private StreamState(AiCanonicalChatRequest request) {
            this.request = request;
        }
    }
}
