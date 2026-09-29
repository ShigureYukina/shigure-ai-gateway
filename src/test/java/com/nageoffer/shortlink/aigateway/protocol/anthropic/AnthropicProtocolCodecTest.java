package com.nageoffer.shortlink.aigateway.protocol.anthropic;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * 双向映射表的互逆性测试。
 * <p>
 * 合并两套 codec 的目的就是消除"改一处漏一处"——所以这里断言的不是单个方向的输出，
 * 而是 <b>A→B→A 必须回到原点</b>。任何一侧的映射被单独改动，这些用例都会红。
 */
class AnthropicProtocolCodecTest {

    @Test
    void shouldRoundTripTools() {
        Map<String, Object> openAiTool = Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "get_weather",
                        "description", "查天气",
                        "parameters", Map.of("type", "object")));

        List<Map<String, Object>> anthropicTools =
                AnthropicProtocolCodec.toAnthropicTools(List.of(openAiTool));
        Assertions.assertEquals("get_weather", anthropicTools.get(0).get("name"));
        Assertions.assertNotNull(anthropicTools.get(0).get("input_schema"));

        List<Map<String, Object>> back = AnthropicProtocolCodec.toOpenAiTools(toJsonArray(anthropicTools));
        Assertions.assertEquals(openAiTool, back.get(0));
    }

    @Test
    void shouldRoundTripToolChoiceForEveryBranch() {
        // auto 两侧同名
        Assertions.assertEquals(Map.of("type", "auto"),
                AnthropicProtocolCodec.toAnthropicToolChoice("auto"));
        Assertions.assertEquals(Map.of("type", "auto"),
                AnthropicProtocolCodec.toOpenAiToolChoice(JSON.parseObject("{\"type\":\"auto\"}")));

        // required <-> any，是两侧唯一的改名点
        Map<String, Object> any = AnthropicProtocolCodec.toAnthropicToolChoice("required");
        Assertions.assertEquals(Map.of("type", "any"), any);
        Assertions.assertEquals(Map.of("type", "required"),
                AnthropicProtocolCodec.toOpenAiToolChoice(toJsonObject(any)));

        // 指定具体工具：Anthropic 用 "tool"（不是 content block 的 "tool_use"）
        Map<String, Object> named = AnthropicProtocolCodec.toAnthropicToolChoice(
                Map.of("type", "function", "function", Map.of("name", "get_weather")));
        Assertions.assertEquals(Map.of("type", "tool", "name", "get_weather"), named);
        Assertions.assertEquals(Map.of("type", "function", "function", Map.of("name", "get_weather")),
                AnthropicProtocolCodec.toOpenAiToolChoice(toJsonObject(named)));

        // none 在 Anthropic 侧没有等价物
        Assertions.assertNull(AnthropicProtocolCodec.toAnthropicToolChoice("none"));
    }

    @Test
    void shouldRoundTripUsage() {
        JSONObject openAiUsage = new JSONObject();
        openAiUsage.put("prompt_tokens", 11);
        openAiUsage.put("completion_tokens", 7);
        openAiUsage.put("total_tokens", 18);

        JSONObject anthropicUsage = AnthropicProtocolCodec.toAnthropicUsage(openAiUsage);
        Assertions.assertEquals(11L, anthropicUsage.getLong("input_tokens"));
        Assertions.assertEquals(7L, anthropicUsage.getLong("output_tokens"));

        JSONObject back = AnthropicProtocolCodec.toOpenAiUsage(anthropicUsage);
        Assertions.assertEquals(11L, back.getLong("prompt_tokens"));
        Assertions.assertEquals(7L, back.getLong("completion_tokens"));
        Assertions.assertEquals(18L, back.getLong("total_tokens"));
    }

    @Test
    void shouldRoundTripStopReasonForEveryTerminalCase() {
        record Case(String openAi, String anthropic) {
        }
        List<Case> cases = List.of(
                new Case("length", "max_tokens"),
                new Case("stop", "end_turn"),
                new Case("tool_calls", "tool_use"));
        for (Case each : cases) {
            String anthropic = AnthropicProtocolCodec.toAnthropicStopReason(each.openAi(), false);
            Assertions.assertEquals(each.anthropic(), anthropic, "OpenAI -> Anthropic: " + each.openAi());
            Assertions.assertEquals(each.openAi(),
                    AnthropicProtocolCodec.toOpenAiFinishReason(anthropic, false),
                    "Anthropic -> OpenAI: " + anthropic);
        }
    }

    @Test
    void shouldKeepBothDirectionsTotalOnNull() {
        // Anthropic 的 stop_reason 不允许为 null，必须兜底；返回 null 会让客户端拿到 "null" 字符串
        Assertions.assertEquals("end_turn", AnthropicProtocolCodec.toAnthropicStopReason(null, false));
        // 反方向相反：上游没给 stop_reason 时不能编一个 "stop"，否则流式早期分片会被当成生成结束
        Assertions.assertNull(AnthropicProtocolCodec.toOpenAiFinishReason(null, false));
        // 有工具调用时两侧都必须收敛到工具语义
        Assertions.assertEquals("tool_use", AnthropicProtocolCodec.toAnthropicStopReason(null, true));
        Assertions.assertEquals("tool_calls", AnthropicProtocolCodec.toOpenAiFinishReason("end_turn", true));
    }

    @Test
    void shouldRoundTripToolCallAndToolUseBlock() {
        Map<String, Object> toolCall = AnthropicProtocolCodec.toOpenAiToolCall(
                "toolu_1", "get_weather", Map.of("city", "北京"));
        Assertions.assertEquals("toolu_1", toolCall.get("id"));

        Map<?, ?> function = (Map<?, ?>) toolCall.get("function");
        Assertions.assertEquals("get_weather", function.get("name"));
        Assertions.assertEquals("{\"city\":\"北京\"}", function.get("arguments"));

        Map<String, Object> block = AnthropicProtocolCodec.toAnthropicToolUseBlock(
                toolCall.get("id"), function.get("name"),
                AnthropicProtocolCodec.parseArguments(function.get("arguments")));
        Assertions.assertEquals("tool_use", block.get("type"));
        Assertions.assertEquals(Map.of("city", "北京"), block.get("input"));
    }

    @Test
    void shouldRoundTripStopSequences() {
        Assertions.assertEquals(List.of("STOP"), AnthropicProtocolCodec.toAnthropicStopSequences("STOP"));
        Assertions.assertEquals(List.of("A", "B"),
                AnthropicProtocolCodec.toAnthropicStopSequences(List.of("A", "B")));
        // 空值与空集合都不能生成非法的空 stop_sequences
        Assertions.assertTrue(AnthropicProtocolCodec.toAnthropicStopSequences(null).isEmpty());
        Assertions.assertTrue(AnthropicProtocolCodec.toAnthropicStopSequences(List.of()).isEmpty());
    }

    @Test
    void shouldKeepStreamingToolCallArgumentsEmptyAtStartFrame() {
        // 起始帧回 "{}" 会把后续 input_json_delta 拼成非法 JSON
        Map<?, ?> function = (Map<?, ?>) AnthropicProtocolCodec
                .toOpenAiStreamingToolCall(2, "toolu_1", "get_weather")
                .get("function");
        Assertions.assertEquals("", function.get("arguments"));
        Assertions.assertEquals(2, AnthropicProtocolCodec
                .toOpenAiStreamingToolCall(2, "toolu_1", "get_weather").get("index"));
    }

    @Test
    void shouldFallBackToClientModelAndNeverReturnBlank() {
        Assertions.assertEquals("client-model", AnthropicProtocolCodec.resolveModel("client-model", "upstream"));
        Assertions.assertEquals("upstream", AnthropicProtocolCodec.resolveModel("  ", "upstream"));
        Assertions.assertEquals("unknown", AnthropicProtocolCodec.resolveModel(null, null));
    }

    @Test
    void shouldMergeUsageByTakingLargerFieldInsteadOfOverwriting() {
        // Anthropic 把 input 放 message_start、output 放 message_delta，且都带对方为 0 的字段
        JSONObject start = new JSONObject();
        start.put("prompt_tokens", 9);
        start.put("completion_tokens", 0);
        start.put("total_tokens", 9);

        JSONObject delta = new JSONObject();
        delta.put("prompt_tokens", 0);
        delta.put("completion_tokens", 3);
        delta.put("total_tokens", 3);

        JSONObject merged = AnthropicProtocolCodec.mergeOpenAiUsage(start, delta);
        Assertions.assertEquals(9L, merged.getLong("prompt_tokens"));
        Assertions.assertEquals(3L, merged.getLong("completion_tokens"));
        Assertions.assertEquals(12L, merged.getLong("total_tokens"));
    }

    private JSONArray toJsonArray(List<Map<String, Object>> source) {
        JSONArray array = new JSONArray();
        array.addAll(source);
        return array;
    }

    private JSONObject toJsonObject(Map<String, Object> source) {
        JSONObject object = new JSONObject();
        object.putAll(source);
        return object;
    }
}
