package com.nageoffer.shortlink.aigateway.protocol.responses;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class OpenAiResponsesMapperTest {

    private final OpenAiResponsesMapper mapper = new OpenAiResponsesMapper();

    @Test
    void shouldMapStringInputWithInstructions() {
        JSONObject body = JSON.parseObject("""
                { "model": "gpt-4o-mini", "instructions": "简洁回答", "input": "你好",
                  "max_output_tokens": 128, "temperature": 0.5, "top_p": 0.8 }
                """);

        AiChatCompletionReqDTO request = mapper.toChatRequest(body);

        Assertions.assertEquals("gpt-4o-mini", request.getModel());
        // max_output_tokens 要落到 chat 链路的 max_tokens 上，否则配额预扣拿不到上限
        Assertions.assertEquals(128, request.getMaxTokens());
        Assertions.assertEquals(0.5D, request.getTemperature());
        Assertions.assertEquals(0.8D, ((Number) request.getUnmapped().get("top_p")).doubleValue(), 0.0001);
        Assertions.assertEquals("system", request.getMessages().get(0).getRole());
        Assertions.assertEquals("简洁回答", request.getMessages().get(0).getContent());
        Assertions.assertEquals("user", request.getMessages().get(1).getRole());
        Assertions.assertEquals("你好", request.getMessages().get(1).getContent());
    }

    @Test
    void shouldMapItemArrayWithFunctionCallRoundTrip() {
        JSONObject functionCall = new JSONObject();
        functionCall.put("type", "function_call");
        functionCall.put("call_id", "call_1");
        functionCall.put("name", "get_weather");
        functionCall.put("arguments", "{\"city\":\"北京\"}");
        JSONObject functionOutput = new JSONObject();
        functionOutput.put("type", "function_call_output");
        functionOutput.put("call_id", "call_1");
        functionOutput.put("output", "晴，26 度");
        JSONArray input = new JSONArray();
        input.add(messageItem("user", "北京天气怎么样"));
        input.add(functionCall);
        input.add(functionOutput);
        JSONObject body = new JSONObject();
        body.put("model", "gpt-4o-mini");
        body.put("input", input);

        List<AiChatCompletionMessage> messages = mapper.toChatRequest(body).getMessages();

        Assertions.assertEquals(3, messages.size());
        AiChatCompletionMessage assistant = messages.get(1);
        Assertions.assertEquals("assistant", assistant.getRole());
        List<?> toolCalls = (List<?>) assistant.getUnmapped().get("tool_calls");
        Map<?, ?> function = (Map<?, ?>) ((Map<?, ?>) toolCalls.get(0)).get("function");
        Assertions.assertEquals("get_weather", function.get("name"));
        AiChatCompletionMessage toolResult = messages.get(2);
        Assertions.assertEquals("tool", toolResult.getRole());
        Assertions.assertEquals("call_1", toolResult.getUnmapped().get("tool_call_id"));
        Assertions.assertEquals("晴，26 度", toolResult.getContent());
    }

    @Test
    void shouldFlattenResponsesToolsIntoChatTools() {
        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        tool.put("name", "get_weather");
        tool.put("description", "查天气");
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");
        tool.put("parameters", parameters);
        JSONArray tools = new JSONArray();
        tools.add(tool);
        JSONObject body = new JSONObject();
        body.put("model", "m");
        body.put("input", "hi");
        body.put("tools", tools);

        List<?> converted = (List<?>) mapper.toChatRequest(body).getUnmapped().get("tools");

        Map<?, ?> entry = (Map<?, ?>) converted.get(0);
        Assertions.assertEquals("function", entry.get("type"));
        Map<?, ?> function = (Map<?, ?>) entry.get("function");
        Assertions.assertEquals("get_weather", function.get("name"));
        Assertions.assertEquals("查天气", function.get("description"));
        Assertions.assertNotNull(function.get("parameters"));
    }

    @Test
    void shouldMapChatResponseToResponsesShape() {
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
        openAiUsage.put("prompt_tokens", 10);
        openAiUsage.put("completion_tokens", 4);
        JSONObject openAi = new JSONObject();
        openAi.put("id", "chatcmpl-1");
        openAi.put("model", "gpt-4o-mini");
        openAi.put("choices", choices);
        openAi.put("usage", openAiUsage);

        JSONObject response = mapper.toResponse(openAi.toJSONString(), "gpt-4o-mini");

        Assertions.assertEquals("response", response.getString("object"));
        Assertions.assertEquals("completed", response.getString("status"));
        Assertions.assertEquals("gpt-4o-mini", response.getString("model"));
        Assertions.assertEquals("好的", response.getString("output_text"));

        JSONArray output = response.getJSONArray("output");
        // 文本消息固定占 0 号输出位，function_call 依次排后
        Assertions.assertEquals("message", output.getJSONObject(0).getString("type"));
        Assertions.assertEquals("好的", output.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"));
        Assertions.assertEquals("function_call", output.getJSONObject(1).getString("type"));
        Assertions.assertEquals("call_1", output.getJSONObject(1).getString("call_id"));

        JSONObject usage = response.getJSONObject("usage");
        Assertions.assertEquals(10L, usage.getLong("input_tokens"));
        Assertions.assertEquals(4L, usage.getLong("output_tokens"));
        Assertions.assertEquals(14L, usage.getLong("total_tokens"));
    }

    private JSONObject messageItem(String role, String text) {
        JSONObject part = new JSONObject();
        part.put("type", "input_text");
        part.put("text", text);
        JSONArray content = new JSONArray();
        content.add(part);
        JSONObject item = new JSONObject();
        item.put("type", "message");
        item.put("role", role);
        item.put("content", content);
        return item;
    }
}
