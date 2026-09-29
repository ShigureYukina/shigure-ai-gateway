package com.nageoffer.shortlink.aigateway.protocol.anthropic;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.governance.ContentTextExtractor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API 与网关内部 OpenAI 形状之间的转换（入口方向）。
 * <p>
 * 这是 {@code ClaudeCompatibleProviderAdapter} 的镜像：那个负责"把 OpenAI 说给 Claude 听"，
 * 这个负责"把 Claude 说给 OpenAI 听"——客户端可以用 Anthropic SDK 直连网关，
 * 背后照样是同一套路由、配额、缓存与治理链路。两侧共用的字段映射表收口在
 * {@link AnthropicProtocolCodec}，这里只负责消息级编排。
 * <p>
 * 支持范围：system（字符串或 text 块数组）、messages 的文本/图片/tool_use/tool_result、
 * tools/tool_choice/stop_sequences/top_p/top_k/metadata/temperature/max_tokens。
 */
@Component
public class AnthropicMessagesMapper {

    /**
     * Anthropic 请求体 -> 网关内部请求。
     */
    public AiChatCompletionReqDTO toChatRequest(JSONObject body) {
        AiChatCompletionReqDTO request = new AiChatCompletionReqDTO();
        request.setModel(body.getString("model"));
        request.setMaxTokens(body.getInteger("max_tokens"));
        request.setTemperature(body.getDouble("temperature"));
        request.setStream(Boolean.TRUE.equals(body.getBoolean("stream")));

        List<AiChatCompletionMessage> messages = new ArrayList<>();
        appendSystemMessage(messages, body.get("system"));
        appendConversation(messages, body.getJSONArray("messages"));
        request.setMessages(messages);

        JSONObject metadata = body.getJSONObject("metadata");
        if (metadata != null && !metadata.isEmpty()) {
            request.setMetadata(new LinkedHashMap<>(metadata));
        }
        AnthropicProtocolCodec.applyAnthropicBodyToOpenAiRequest(request, body);
        return request;
    }

    /**
     * 网关内部响应 -> Anthropic Messages 响应。
     */
    public JSONObject toMessageResponse(String openAiBody, String clientModel) {
        JSONObject openAi = JSON.parseObject(openAiBody);
        JSONObject choice = firstChoice(openAi);
        JSONObject message = choice == null ? null : choice.getJSONObject("message");

        JSONArray content = new JSONArray();
        String text = message == null ? null : message.getString("content");
        if (StringUtils.hasText(text)) {
            content.add(AnthropicProtocolCodec.toAnthropicTextBlock(text));
        }
        boolean hasToolUse = false;
        JSONArray toolCalls = message == null ? null : message.getJSONArray(AnthropicProtocolCodec.FIELD_TOOL_CALLS);
        if (toolCalls != null) {
            for (int index = 0; index < toolCalls.size(); index++) {
                JSONObject toolCall = toolCalls.getJSONObject(index);
                if (toolCall == null) {
                    continue;
                }
                content.add(toToolUseBlock(toolCall));
                hasToolUse = true;
            }
        }

        JSONObject response = new JSONObject();
        response.put("id", openAi.getString("id"));
        response.put("type", "message");
        response.put("role", "assistant");
        response.put("model", AnthropicProtocolCodec.resolveModel(clientModel, openAi.getString("model")));
        response.put("content", content);
        response.put("stop_reason", AnthropicProtocolCodec.toAnthropicStopReason(
                choice == null ? null : choice.getString("finish_reason"), hasToolUse));
        response.put("stop_sequence", null);
        JSONObject usage = AnthropicProtocolCodec.toAnthropicUsage(openAi.getJSONObject("usage"));
        if (usage != null) {
            response.put("usage", usage);
        }
        return response;
    }

    /**
     * 统计输入 token：Anthropic 客户端会在发请求前调一次，用来做上下文预算。
     */
    public int countInputTokens(JSONObject body) {
        StringBuilder builder = new StringBuilder();
        String systemText = ContentTextExtractor.text(body.get("system"));
        if (StringUtils.hasText(systemText)) {
            builder.append(systemText);
        }
        JSONArray messages = body.getJSONArray("messages");
        if (messages != null) {
            for (int index = 0; index < messages.size(); index++) {
                JSONObject message = messages.getJSONObject(index);
                if (message != null) {
                    builder.append(ContentTextExtractor.text(message.get("content")));
                }
            }
        }
        // 与 TokenEstimator 保持同一口径（约 4 字符 1 token），避免两处估算相差一倍
        return Math.max(1, builder.length() / 4);
    }

    private void appendSystemMessage(List<AiChatCompletionMessage> messages, Object system) {
        String text = ContentTextExtractor.text(system);
        if (!StringUtils.hasText(text)) {
            return;
        }
        messages.add(simpleMessage("system", text));
    }

    private void appendConversation(List<AiChatCompletionMessage> messages, JSONArray items) {
        if (items == null) {
            return;
        }
        for (int index = 0; index < items.size(); index++) {
            JSONObject item = items.getJSONObject(index);
            if (item == null) {
                continue;
            }
            String role = item.getString("role");
            Object content = item.get("content");
            if (content instanceof CharSequence text) {
                messages.add(simpleMessage(role, text.toString()));
                continue;
            }
            if (!(content instanceof JSONArray blocks)) {
                messages.add(simpleMessage(role, content));
                continue;
            }
            appendBlockMessage(messages, role, blocks);
        }
    }

    /**
     * 一个 Anthropic 的 content 数组可能同时包含文本、图片、工具调用、工具结果，
     * 而 OpenAI 要把它们拆成不同角色的消息，所以这里按块类型分流。
     */
    private void appendBlockMessage(List<AiChatCompletionMessage> messages, String role, JSONArray blocks) {
        List<Object> parts = new ArrayList<>();
        List<JSONObject> toolUses = new ArrayList<>();
        List<JSONObject> toolResults = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            JSONObject block = blocks.getJSONObject(index);
            if (block == null) {
                continue;
            }
            String type = block.getString("type");
            if (AnthropicProtocolCodec.BLOCK_TOOL_RESULT.equals(type)) {
                toolResults.add(block);
            } else if (AnthropicProtocolCodec.BLOCK_TOOL_USE.equals(type)) {
                toolUses.add(block);
            } else if (AnthropicProtocolCodec.BLOCK_IMAGE.equals(type)) {
                parts.add(toImagePart(block));
            } else if (AnthropicProtocolCodec.BLOCK_TEXT.equals(type) || type == null) {
                parts.add(AnthropicProtocolCodec.toAnthropicTextBlock(
                        block.getString("text") == null ? "" : block.getString("text")));
            }
        }

        if (!toolUses.isEmpty()) {
            messages.add(assistantToolCallMessage(role, parts, toolUses));
        } else if (!parts.isEmpty()) {
            messages.add(contentMessage(role, parts));
        }
        for (JSONObject toolResult : toolResults) {
            messages.add(toolResultMessage(toolResult));
        }
    }

    private AiChatCompletionMessage simpleMessage(String role, Object content) {
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    /**
     * 纯文本时收敛成字符串：不少非 OpenAI 上游只认 string content，数组会让它们直接 400。
     */
    private AiChatCompletionMessage contentMessage(String role, List<Object> parts) {
        boolean textOnly = parts.stream().allMatch(part -> part instanceof Map<?, ?> map
                && AnthropicProtocolCodec.BLOCK_TEXT.equals(String.valueOf(map.get("type"))));
        if (textOnly) {
            StringBuilder builder = new StringBuilder();
            parts.forEach(part -> builder.append(((Map<?, ?>) part).get("text")));
            return simpleMessage(role, builder.toString());
        }
        return simpleMessage(role, parts);
    }

    private AiChatCompletionMessage assistantToolCallMessage(String role, List<Object> parts,
                                                             List<JSONObject> toolUses) {
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole(role);
        String text = parts.stream()
                .filter(part -> part instanceof Map<?, ?> map
                        && AnthropicProtocolCodec.BLOCK_TEXT.equals(String.valueOf(map.get("type"))))
                .map(part -> String.valueOf(((Map<?, ?>) part).get("text")))
                .reduce("", String::concat);
        message.setContent(StringUtils.hasText(text) ? text : null);

        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (JSONObject toolUse : toolUses) {
            toolCalls.add(AnthropicProtocolCodec.toOpenAiToolCall(
                    toolUse.getString("id"), toolUse.getString("name"), toolUse.get("input")));
        }
        message.getUnmapped().put(AnthropicProtocolCodec.FIELD_TOOL_CALLS, toolCalls);
        return message;
    }

    private AiChatCompletionMessage toolResultMessage(JSONObject toolResult) {
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("tool");
        message.setContent(ContentTextExtractor.text(toolResult.get("content")));
        message.getUnmapped().putAll(AnthropicProtocolCodec.toOpenAiToolResultFields(
                toolResult.getString("tool_use_id")));
        return message;
    }

    /**
     * Anthropic 图片块 -> OpenAI 多模态片段（base64 走 data URI）。
     */
    private Map<String, Object> toImagePart(JSONObject block) {
        JSONObject source = block.getJSONObject("source");
        String url = null;
        if (source != null) {
            if ("base64".equals(source.getString("type"))) {
                url = "data:" + source.getString("media_type") + ";base64," + source.getString("data");
            } else {
                url = source.getString("url");
            }
        }
        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", url);
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "image_url");
        part.put("image_url", imageUrl);
        return part;
    }

    /**
     * OpenAI tool_calls 条目 -> Anthropic tool_use 块。
     */
    private Map<String, Object> toToolUseBlock(JSONObject toolCall) {
        JSONObject function = toolCall.getJSONObject("function");
        return AnthropicProtocolCodec.toAnthropicToolUseBlock(
                toolCall.getString("id"),
                function == null ? null : function.getString("name"),
                AnthropicProtocolCodec.parseArguments(function == null ? null : function.getString("arguments")));
    }

    private JSONObject firstChoice(JSONObject openAi) {
        JSONArray choices = openAi.getJSONArray("choices");
        return choices == null || choices.isEmpty() ? null : choices.getJSONObject(0);
    }
}
