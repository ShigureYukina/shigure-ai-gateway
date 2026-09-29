package com.nageoffer.shortlink.aigateway.protocol.responses;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI Responses API 与网关内部 OpenAI Chat 形状之间的转换。
 * <p>
 * Responses 是 Chat Completions 之后的新一代接口：{@code input}/{@code output} 是"条目数组"，
 * 文本要走 {@code output_text} 内容块。这里把它折成网关已有的 chat 链路，
 * 好处是所有治理能力（路由、配额、缓存、安全、可观测）自动复用，不必再造一套数据面。
 * <p>
 * 明确支持：字符串/条目数组 input、instructions、max_output_tokens、temperature、top_p、
 * function 工具与 function_call/function_call_output 回填、文本与非流式/流式输出。
 * <b>不支持</b>：{@code previous_response_id} 会话续接、内置工具（web_search 等）、
 * reasoning 条目、图片生成、background 模式——这些需要网关持有会话状态，不在本实现范围内。
 */
@Slf4j
@Component
public class OpenAiResponsesMapper {

    /**
     * Responses 请求体 -> 网关内部请求。
     */
    public AiChatCompletionReqDTO toChatRequest(JSONObject body) {
        AiChatCompletionReqDTO request = new AiChatCompletionReqDTO();
        request.setModel(body.getString("model"));
        Integer maxOutputTokens = body.getInteger("max_output_tokens");
        request.setMaxTokens(maxOutputTokens == null ? body.getInteger("max_tokens") : maxOutputTokens);
        request.setTemperature(body.getDouble("temperature"));
        request.setStream(Boolean.TRUE.equals(body.getBoolean("stream")));

        List<AiChatCompletionMessage> messages = new ArrayList<>();
        appendInstructions(messages, body.get("instructions"));
        appendInput(messages, body.get("input"));
        request.setMessages(messages);

        JSONObject metadata = body.getJSONObject("metadata");
        if (metadata != null && !metadata.isEmpty()) {
            request.setMetadata(new LinkedHashMap<>(metadata));
        }
        Object topP = body.get("top_p");
        if (topP != null) {
            request.getUnmapped().put("top_p", topP);
        }
        JSONArray tools = body.getJSONArray("tools");
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> converted = toChatTools(tools);
            if (!converted.isEmpty()) {
                request.getUnmapped().put("tools", converted);
            }
        }
        Object toolChoice = body.get("tool_choice");
        if (toolChoice != null) {
            request.getUnmapped().put("tool_choice", toolChoice);
        }
        return request;
    }

    /**
     * 网关内部响应 -> Responses 响应。
     */
    public JSONObject toResponse(String openAiBody, String clientModel) {
        JSONObject openAi = JSON.parseObject(openAiBody);
        JSONArray choices = openAi.getJSONArray("choices");
        JSONObject choice = choices == null || choices.isEmpty() ? null : choices.getJSONObject(0);
        JSONObject message = choice == null ? null : choice.getJSONObject("message");

        String responseId = "resp_" + UUID.randomUUID().toString().replace("-", "");
        String itemId = "msg_" + UUID.randomUUID().toString().replace("-", "");

        StringBuilder text = new StringBuilder();
        JSONArray output = new JSONArray();
        JSONArray content = new JSONArray();

        String mainText = message == null ? null : message.getString("content");
        if (StringUtils.hasText(mainText)) {
            text.append(mainText);
        }
        JSONArray toolCalls = message == null ? null : message.getJSONArray("tool_calls");
        if (toolCalls != null) {
            for (int index = 0; index < toolCalls.size(); index++) {
                JSONObject toolCall = toolCalls.getJSONObject(index);
                if (toolCall == null) {
                    continue;
                }
                JSONObject function = toolCall.getJSONObject("function");
                JSONObject item = new JSONObject();
                item.put("id", "fc_" + UUID.randomUUID().toString().replace("-", ""));
                item.put("type", "function_call");
                item.put("status", "completed");
                item.put("call_id", toolCall.getString("id"));
                item.put("name", function == null ? null : function.getString("name"));
                item.put("arguments", function == null ? "{}" : function.getString("arguments"));
                output.add(item);
            }
        }

        boolean hasText = text.length() > 0;
        if (hasText) {
            JSONObject part = new JSONObject();
            part.put("type", "output_text");
            part.put("text", text.toString());
            part.put("annotations", new JSONArray());
            content.add(part);

            JSONObject item = new JSONObject();
            item.put("id", itemId);
            item.put("type", "message");
            item.put("status", "completed");
            item.put("role", "assistant");
            item.put("content", content);
            // 文本消息按协议固定在第 0 个输出条目
            output.add(0, item);
        }

        JSONObject response = new JSONObject();
        response.put("id", responseId);
        response.put("object", "response");
        response.put("created_at", Instant.now().getEpochSecond());
        response.put("status", "completed");
        response.put("model", StringUtils.hasText(clientModel) ? clientModel : openAi.getString("model"));
        response.put("output", output);
        response.put("output_text", text.toString());
        response.put("error", null);
        response.put("incomplete_details", null);
        JSONObject usage = toResponsesUsage(openAi.getJSONObject("usage"));
        if (usage != null) {
            response.put("usage", usage);
        }
        return response;
    }

    /**
     * Responses 的工具定义是扁平的（name/parameters 与 type 同级），
     * 而 chat 链路用的是嵌套 function 结构，这里做一次结构搬运。
     */
    private List<Map<String, Object>> toChatTools(JSONArray tools) {
        List<Map<String, Object>> converted = new ArrayList<>();
        for (int index = 0; index < tools.size(); index++) {
            JSONObject tool = tools.getJSONObject(index);
            if (tool == null || !"function".equals(tool.getString("type"))) {
                continue;
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.getString("name"));
            if (tool.getString("description") != null) {
                function.put("description", tool.getString("description"));
            }
            function.put("parameters", tool.get("parameters") == null
                    ? Map.of("type", "object", "properties", Map.of())
                    : tool.get("parameters"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "function");
            entry.put("function", function);
            converted.add(entry);
        }
        return converted;
    }

    private void appendInstructions(List<AiChatCompletionMessage> messages, Object instructions) {
        String text = textOf(instructions);
        if (StringUtils.hasText(text)) {
            messages.add(simpleMessage("system", text));
        }
    }

    private void appendInput(List<AiChatCompletionMessage> messages, Object input) {
        if (input == null) {
            return;
        }
        if (input instanceof CharSequence text) {
            messages.add(simpleMessage("user", text.toString()));
            return;
        }
        if (!(input instanceof JSONArray items)) {
            messages.add(simpleMessage("user", String.valueOf(input)));
            return;
        }
        for (int index = 0; index < items.size(); index++) {
            Object raw = items.get(index);
            if (!(raw instanceof JSONObject item)) {
                continue;
            }
            String type = item.getString("type");
            if ("function_call".equals(type)) {
                messages.add(functionCallMessage(item));
            } else if ("function_call_output".equals(type)) {
                messages.add(functionCallOutputMessage(item));
            } else {
                // message 条目（type 可省略）：role + content
                String role = item.getString("role") == null ? "user" : item.getString("role");
                messages.add(contentMessage(role, item.get("content")));
            }
        }
    }

    private AiChatCompletionMessage functionCallMessage(JSONObject item) {
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("assistant");
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", item.getString("name"));
        function.put("arguments", item.getString("arguments") == null ? "{}" : item.getString("arguments"));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", item.getString("call_id"));
        toolCall.put("type", "function");
        toolCall.put("function", function);
        message.getUnmapped().put("tool_calls", List.of(toolCall));
        return message;
    }

    private AiChatCompletionMessage functionCallOutputMessage(JSONObject item) {
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("tool");
        message.setContent(textOf(item.get("output")));
        message.getUnmapped().put("tool_call_id", item.getString("call_id"));
        return message;
    }

    /**
     * message 条目的 content 可能是字符串，也可能是 input_text / input_image 内容块数组。
     */
    private AiChatCompletionMessage contentMessage(String role, Object content) {
        if (content == null || content instanceof CharSequence) {
            return simpleMessage(role, content == null ? "" : content.toString());
        }
        if (!(content instanceof JSONArray parts)) {
            return simpleMessage(role, String.valueOf(content));
        }
        List<Object> converted = new ArrayList<>();
        boolean textOnly = true;
        for (int index = 0; index < parts.size(); index++) {
            JSONObject part = parts.getJSONObject(index);
            if (part == null) {
                continue;
            }
            String type = part.getString("type");
            if ("input_image".equals(type)) {
                textOnly = false;
                Map<String, Object> imageUrl = new LinkedHashMap<>();
                imageUrl.put("url", part.getString("image_url"));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", "image_url");
                entry.put("image_url", imageUrl);
                converted.add(entry);
            } else {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", "text");
                entry.put("text", part.getString("text") == null ? "" : part.getString("text"));
                converted.add(entry);
            }
        }
        if (textOnly) {
            StringBuilder builder = new StringBuilder();
            converted.forEach(part -> builder.append(((Map<?, ?>) part).get("text")));
            return simpleMessage(role, builder.toString());
        }
        return simpleMessage(role, converted);
    }

    private AiChatCompletionMessage simpleMessage(String role, Object content) {
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private JSONObject toResponsesUsage(JSONObject openAiUsage) {
        if (openAiUsage == null || openAiUsage.isEmpty()) {
            return null;
        }
        long input = openAiUsage.getLong("prompt_tokens") == null ? 0L : openAiUsage.getLong("prompt_tokens");
        long output = openAiUsage.getLong("completion_tokens") == null ? 0L : openAiUsage.getLong("completion_tokens");
        JSONObject usage = new JSONObject();
        usage.put("input_tokens", input);
        usage.put("output_tokens", output);
        usage.put("total_tokens", input + output);
        return usage;
    }

    private String textOf(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof CharSequence text) {
            return text.toString();
        }
        if (content instanceof JSONArray parts) {
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < parts.size(); index++) {
                Object part = parts.get(index);
                if (part instanceof JSONObject block && block.get("text") != null) {
                    builder.append(block.get("text"));
                } else if (part instanceof CharSequence textPart) {
                    builder.append(textPart);
                }
            }
            return builder.toString();
        }
        if (content instanceof List<?> parts) {
            StringBuilder builder = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map && map.get("text") != null) {
                    builder.append(map.get("text"));
                }
            }
            return builder.toString();
        }
        return String.valueOf(content);
    }
}
