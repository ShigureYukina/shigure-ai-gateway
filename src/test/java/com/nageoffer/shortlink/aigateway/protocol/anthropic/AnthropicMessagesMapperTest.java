package com.nageoffer.shortlink.aigateway.protocol.anthropic;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class AnthropicMessagesMapperTest {

    private final AnthropicMessagesMapper mapper = new AnthropicMessagesMapper();

    @Test
    void shouldMapSystemTextAndToolsToChatShape() {
        JSONObject body = JSON.parseObject("""
                {
                  "model": "claude-3-5-sonnet-compatible",
                  "max_tokens": 256,
                  "system": "你是网关助手",
                  "temperature": 0.3,
                  "top_p": 0.9,
                  "stop_sequences": ["STOP"],
                  "tool_choice": { "type": "any" },
                  "tools": [
                    { "name": "get_weather", "description": "查天气",
                      "input_schema": { "type": "object", "properties": { "city": { "type": "string" } } } }
                  ],
                  "messages": [ { "role": "user", "content": "北京天气" } ]
                }
                """);

        AiChatCompletionReqDTO request = mapper.toChatRequest(body);

        Assertions.assertEquals("claude-3-5-sonnet-compatible", request.getModel());
        Assertions.assertEquals(256, request.getMaxTokens());
        Assertions.assertEquals(0.3D, request.getTemperature());
        Assertions.assertEquals("system", request.getMessages().get(0).getRole());
        Assertions.assertEquals("你是网关助手", request.getMessages().get(0).getContent());
        Assertions.assertEquals("user", request.getMessages().get(1).getRole());
        // stop_sequences 与 OpenAI 的 stop 语义等价，必须改名
        Assertions.assertEquals(List.of("STOP"), request.getUnmapped().get("stop"));
        Assertions.assertEquals(Map.of("type", "required"), request.getUnmapped().get("tool_choice"));

        List<?> tools = (List<?>) request.getUnmapped().get("tools");
        Map<?, ?> function = (Map<?, ?>) ((Map<?, ?>) tools.get(0)).get("function");
        Assertions.assertEquals("get_weather", function.get("name"));
        // input_schema -> parameters
        Assertions.assertNotNull(function.get("parameters"));
    }

    @Test
    void shouldSplitToolUseAndToolResultIntoOpenAiMessages() {
        JSONObject body = JSON.parseObject("""
                {
                  "model": "m", "max_tokens": 64,
                  "messages": [
                    { "role": "assistant", "content": [
                        { "type": "text", "text": "我来查一下" },
                        { "type": "tool_use", "id": "toolu_1", "name": "get_weather", "input": { "city": "北京" } }
                    ] },
                    { "role": "user", "content": [
                        { "type": "tool_result", "tool_use_id": "toolu_1", "content": "晴，26 度" }
                    ] }
                  ]
                }
                """);

        List<AiChatCompletionMessage> messages = mapper.toChatRequest(body).getMessages();

        Assertions.assertEquals(2, messages.size());
        AiChatCompletionMessage assistant = messages.get(0);
        Assertions.assertEquals("assistant", assistant.getRole());
        Assertions.assertEquals("我来查一下", assistant.getContent());
        List<?> toolCalls = (List<?>) assistant.getUnmapped().get("tool_calls");
        Map<?, ?> function = (Map<?, ?>) ((Map<?, ?>) toolCalls.get(0)).get("function");
        Assertions.assertEquals("get_weather", function.get("name"));
        Assertions.assertEquals("{\"city\":\"北京\"}", function.get("arguments"));

        AiChatCompletionMessage toolResult = messages.get(1);
        Assertions.assertEquals("tool", toolResult.getRole());
        Assertions.assertEquals("晴，26 度", toolResult.getContent());
        Assertions.assertEquals("toolu_1", toolResult.getUnmapped().get("tool_call_id"));
    }

    @Test
    void shouldConvertBase64ImageAndCollapseTextOnlyBlocks() {
        JSONObject body = JSON.parseObject("""
                {
                  "model": "m", "max_tokens": 64,
                  "messages": [
                    { "role": "user", "content": [
                        { "type": "text", "text": "看这张图" },
                        { "type": "image", "source": { "type": "base64", "media_type": "image/png", "data": "AAAA" } }
                    ] },
                    { "role": "user", "content": [ { "type": "text", "text": "只有" }, { "type": "text", "text": "文本" } ] }
                  ]
                }
                """);

        List<AiChatCompletionMessage> messages = mapper.toChatRequest(body).getMessages();

        // 含图片时保持内容块数组
        List<?> multimodal = (List<?>) messages.get(0).getContent();
        Map<?, ?> imageUrl = (Map<?, ?>) ((Map<?, ?>) multimodal.get(1)).get("image_url");
        Assertions.assertEquals("data:image/png;base64,AAAA", imageUrl.get("url"));
        // 纯文本时收敛成字符串，避免非 OpenAI 上游因数组内容 400
        Assertions.assertEquals("只有文本", messages.get(1).getContent());
    }

    @Test
    void shouldMapChatResponseToAnthropicMessage() {
        // 用具名对象拼 OpenAI 响应体：文本块里再套一层 JSON 转义太容易写错
        JSONObject function = new JSONObject();
        function.put("name", "get_weather");
        function.put("arguments", "{\"city\":\"北京\"}");
        JSONObject toolCall = new JSONObject();
        toolCall.put("id", "call_1");
        toolCall.put("type", "function");
        toolCall.put("function", function);
        JSONArray toolCalls = new JSONArray();
        toolCalls.add(toolCall);
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", "好的");
        message.put("tool_calls", toolCalls);
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("finish_reason", "tool_calls");
        choice.put("message", message);
        JSONArray choices = new JSONArray();
        choices.add(choice);
        JSONObject openAiUsage = new JSONObject();
        openAiUsage.put("prompt_tokens", 12);
        openAiUsage.put("completion_tokens", 8);
        JSONObject openAi = new JSONObject();
        openAi.put("id", "chatcmpl-1");
        openAi.put("choices", choices);
        openAi.put("usage", openAiUsage);

        JSONObject response = mapper.toMessageResponse(openAi.toJSONString(), "claude-3-5-sonnet-compatible");

        Assertions.assertEquals("message", response.getString("type"));
        Assertions.assertEquals("assistant", response.getString("role"));
        Assertions.assertEquals("claude-3-5-sonnet-compatible", response.getString("model"));
        Assertions.assertEquals("tool_use", response.getString("stop_reason"));

        JSONArray content = response.getJSONArray("content");
        Assertions.assertEquals("text", content.getJSONObject(0).getString("type"));
        JSONObject toolUse = content.getJSONObject(1);
        Assertions.assertEquals("tool_use", toolUse.getString("type"));
        Assertions.assertEquals("get_weather", toolUse.getString("name"));
        Assertions.assertEquals("北京", toolUse.getJSONObject("input").getString("city"));

        JSONObject usage = response.getJSONObject("usage");
        Assertions.assertEquals(12L, usage.getLong("input_tokens"));
        Assertions.assertEquals(8L, usage.getLong("output_tokens"));
    }

    @Test
    void shouldMapLengthFinishReasonAndCountInputTokens() {
        String openAiBody = """
                { "id": "c", "choices": [ { "index": 0, "finish_reason": "length",
                  "message": { "role": "assistant", "content": "被截断" } } ] }
                """;

        JSONObject response = mapper.toMessageResponse(openAiBody, null);

        Assertions.assertEquals("max_tokens", response.getString("stop_reason"));
        Assertions.assertEquals("c", response.getString("id"));

        JSONObject body = JSON.parseObject("""
                { "system": "12345678", "messages": [ { "role": "user", "content": "12345678" } ] }
                """);
        Assertions.assertEquals(4, mapper.countInputTokens(body));
    }
}
