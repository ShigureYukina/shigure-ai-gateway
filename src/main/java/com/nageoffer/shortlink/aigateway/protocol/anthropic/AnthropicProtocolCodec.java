package com.nageoffer.shortlink.aigateway.protocol.anthropic;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages 协议与网关内部 OpenAI 形状之间的双向字段映射表。
 * <p>
 * 两条方向原本各持一份映射表：
 * <ul>
 *   <li>出口 {@code ClaudeCompatibleProviderAdapter}：把 OpenAI 说给 Claude 听（Anthropic 上游）；</li>
 *   <li>入口 {@code AnthropicMessagesMapper} / {@code AnthropicStreamMapper}：把 Claude 说给 OpenAI 听
 *       （客户端用 Anthropic SDK 直连网关）。</li>
 * </ul>
 * 两份互为镜像——{@code stop} ↔ {@code stop_sequences}、{@code function.parameters} ↔ {@code input_schema}、
 * {@code function.arguments} ↔ {@code input}、{@code prompt/completion_tokens} ↔ {@code input/output_tokens}、
 * {@code tool_calls} ↔ {@code tool_use} 块。散在两个包里时，改一处漏一处不会有任何编译期报错，
 * 只会在真实流量上表现为"某个方向突然解析失败"，所以映射表在这里收成唯一一份。
 * <p>
 * 刻意不合并的边界：
 * <ul>
 *   <li>消息级编排留在各自类里——入口侧产出 {@link com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage}，
 *       出口侧消费 {@code Map<String,Object>}，容器类型不同，强行统一只会引入无谓的中间转换；</li>
 *   <li>两个流式状态机方向相反（Anthropic 事件 → OpenAI chunk / OpenAI chunk → Anthropic 事件），
 *       状态字段没有交集；共用的只有事件载荷构造与 stop_reason 映射，两者已在此收口。</li>
 * </ul>
 * <p>
 * 纯函数、无状态，因此用静态工具类而非容器 Bean：既避免给三个适配器各加一个构造参数，
 * 也让两侧能直接在单测里断言映射结果。
 */
@Slf4j
public final class AnthropicProtocolCodec {

    /**
     * OpenAI SSE 的结束标记，两侧流式转换共用。
     */
    public static final String DONE_PAYLOAD = "[DONE]";

    /**
     * Anthropic content block 类型。
     */
    public static final String BLOCK_TEXT = "text";

    public static final String BLOCK_IMAGE = "image";

    public static final String BLOCK_TOOL_USE = "tool_use";

    public static final String BLOCK_TOOL_RESULT = "tool_result";

    /**
     * 内部 canonical 形状（OpenAI chat）上与工具相关的字段名。
     * <p>
     * 入口方向负责产出、出口方向负责消费，必须同名；这类"跨方向的隐式契约"才是真正会漏改的地方，
     * 因此连字段名一起收口。协议自身固定的字段（type/role/content/name）两侧拼写一致，不在此列。
     */
    public static final String FIELD_TOOL_CALLS = "tool_calls";

    public static final String FIELD_TOOL_CALL_ID = "tool_call_id";

    private static final String FIELD_TYPE = "type";

    private static final String FIELD_ROLE = "role";

    private static final String FIELD_CONTENT = "content";

    private static final String FIELD_NAME = "name";

    private static final String FIELD_DESCRIPTION = "description";

    private static final String FIELD_FUNCTION = "function";

    private static final String FIELD_ARGUMENTS = "arguments";

    private static final String FIELD_PARAMETERS = "parameters";

    private static final String FIELD_INPUT = "input";

    private static final String FIELD_INPUT_SCHEMA = "input_schema";

    private static final String FIELD_TOOL_USE_ID = "tool_use_id";

    private static final String FIELD_TOOL_USE = BLOCK_TOOL_USE;

    private static final String FIELD_TOP_P = "top_p";

    private static final String FIELD_TOP_K = "top_k";

    private static final String FIELD_METADATA = "metadata";

    private static final String FIELD_STOP = "stop";

    private static final String FIELD_STOP_SEQUENCES = "stop_sequences";

    private static final String FIELD_TOOLS = "tools";

    private static final String FIELD_TOOL_CHOICE = "tool_choice";

    private static final String FIELD_PROMPT_TOKENS = "prompt_tokens";

    private static final String FIELD_COMPLETION_TOKENS = "completion_tokens";

    private static final String FIELD_TOTAL_TOKENS = "total_tokens";

    private static final String FIELD_INPUT_TOKENS = "input_tokens";

    private static final String FIELD_OUTPUT_TOKENS = "output_tokens";

    private static final String TYPE_FUNCTION = "function";

    private static final String TYPE_AUTO = "auto";

    /**
     * Anthropic tool_choice 指定具体工具时用的是 {@code "tool"}，与 content block 的
     * {@link #BLOCK_TOOL_USE}（{@code "tool_use"}）不是同一个词，两者极易写混。
     */
    private static final String TYPE_TOOL_CHOICE = "tool";

    private AnthropicProtocolCodec() {
    }

    // ------------------------------------------------------------------
    // 方向一：Anthropic -> OpenAI
    // ------------------------------------------------------------------

    /**
     * Anthropic tools（{@code {name, description, input_schema}}）-> OpenAI tools。
     */
    public static List<Map<String, Object>> toOpenAiTools(JSONArray tools) {
        List<Map<String, Object>> converted = new ArrayList<>();
        if (tools == null) {
            return converted;
        }
        for (int index = 0; index < tools.size(); index++) {
            JSONObject tool = tools.getJSONObject(index);
            if (tool == null) {
                continue;
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put(FIELD_NAME, tool.getString(FIELD_NAME));
            if (tool.getString(FIELD_DESCRIPTION) != null) {
                function.put(FIELD_DESCRIPTION, tool.getString(FIELD_DESCRIPTION));
            }
            function.put(FIELD_PARAMETERS, tool.get(FIELD_INPUT_SCHEMA) == null
                    ? Map.of(FIELD_TYPE, "object")
                    : tool.get(FIELD_INPUT_SCHEMA));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(FIELD_TYPE, TYPE_FUNCTION);
            entry.put(FIELD_FUNCTION, function);
            converted.add(entry);
        }
        return converted;
    }

    /**
     * Anthropic tool_choice -> OpenAI tool_choice。
     * <p>
     * 语义等价但拼写不同：{@code any} 在 OpenAI 侧叫 {@code required}。
     */
    public static Map<String, Object> toOpenAiToolChoice(JSONObject toolChoice) {
        if (toolChoice == null) {
            return null;
        }
        String type = toolChoice.getString(FIELD_TYPE);
        if (TYPE_AUTO.equals(type)) {
            return Map.of(FIELD_TYPE, TYPE_AUTO);
        }
        if ("any".equals(type)) {
            return Map.of(FIELD_TYPE, "required");
        }
        if (TYPE_TOOL_CHOICE.equals(type) && StringUtils.hasText(toolChoice.getString(FIELD_NAME))) {
            return Map.of(FIELD_TYPE, TYPE_FUNCTION,
                    FIELD_FUNCTION, Map.of(FIELD_NAME, toolChoice.getString(FIELD_NAME)));
        }
        if ("none".equals(type)) {
            return Map.of(FIELD_TYPE, "none");
        }
        return null;
    }

    /**
     * Anthropic usage -> OpenAI usage。
     */
    public static JSONObject toOpenAiUsage(JSONObject anthropicUsage) {
        if (anthropicUsage == null || anthropicUsage.isEmpty()) {
            return null;
        }
        Long inputTokens = anthropicUsage.getLong(FIELD_INPUT_TOKENS);
        Long outputTokens = anthropicUsage.getLong(FIELD_OUTPUT_TOKENS);
        if (inputTokens == null && outputTokens == null) {
            return null;
        }
        long prompt = inputTokens == null ? 0L : inputTokens;
        long completion = outputTokens == null ? 0L : outputTokens;
        JSONObject usage = new JSONObject();
        usage.put(FIELD_PROMPT_TOKENS, prompt);
        usage.put(FIELD_COMPLETION_TOKENS, completion);
        usage.put(FIELD_TOTAL_TOKENS, prompt + completion);
        return usage;
    }

    /**
     * 合并两次事件里的用量。
     * <p>
     * Anthropic 把 input_tokens 放在 message_start、output_tokens 放在 message_delta，
     * 且两边都会带上对方为 0 的字段。直接覆盖会让先到的字段被清零，
     * 最终结算的 token 数少一大截，因此这里按字段取较大值并重算总数。
     */
    public static JSONObject mergeOpenAiUsage(JSONObject accumulated, JSONObject incoming) {
        if (accumulated == null) {
            return incoming;
        }
        if (incoming == null) {
            return accumulated;
        }
        long prompt = Math.max(accumulated.getLongValue(FIELD_PROMPT_TOKENS), incoming.getLongValue(FIELD_PROMPT_TOKENS));
        long completion = Math.max(accumulated.getLongValue(FIELD_COMPLETION_TOKENS),
                incoming.getLongValue(FIELD_COMPLETION_TOKENS));
        JSONObject merged = new JSONObject();
        merged.put(FIELD_PROMPT_TOKENS, prompt);
        merged.put(FIELD_COMPLETION_TOKENS, completion);
        merged.put(FIELD_TOTAL_TOKENS, prompt + completion);
        return merged;
    }

    /**
     * Anthropic stop_reason -> OpenAI finish_reason。
     * <p>
     * 注意：上游没给 stop_reason 时返回 {@code null} 而不是 "stop"——流式早期分片会带空 stop_reason，
     * 提前回一个 "stop" 会让客户端以为生成已结束。
     */
    public static String toOpenAiFinishReason(String anthropicStopReason, boolean hasToolCall) {
        if (hasToolCall) {
            return FIELD_TOOL_CALLS;
        }
        if (anthropicStopReason == null) {
            return null;
        }
        return switch (anthropicStopReason) {
            case "max_tokens" -> "length";
            case FIELD_TOOL_USE -> FIELD_TOOL_CALLS;
            default -> "stop";
        };
    }

    /**
     * Anthropic tool_use 块 -> OpenAI tool_calls 条目。
     */
    public static Map<String, Object> toOpenAiToolCall(Object id, Object name, Object input) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put(FIELD_NAME, name);
        function.put(FIELD_ARGUMENTS, input == null ? "{}" : JSON.toJSONString(input));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", id);
        toolCall.put(FIELD_TYPE, TYPE_FUNCTION);
        toolCall.put(FIELD_FUNCTION, function);
        return toolCall;
    }

    /**
     * OpenAI <b>流式</b> tool_calls 的起始条目。
     * <p>
     * 与 {@link #toOpenAiToolCall} 的区别：流式条目要带 {@code index} 供客户端按块归位，
     * 且 {@code arguments} 必须是空串——起始帧就回 "{}" 会把后面的增量拼成非法 JSON。
     */
    public static Map<String, Object> toOpenAiStreamingToolCall(int index, Object id, Object name) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put(FIELD_NAME, name);
        function.put(FIELD_ARGUMENTS, "");
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("index", index);
        toolCall.put("id", id);
        toolCall.put(FIELD_TYPE, TYPE_FUNCTION);
        toolCall.put(FIELD_FUNCTION, function);
        return toolCall;
    }

    /**
     * OpenAI {@code tool} 角色消息所需的扩展字段。
     * <p>
     * 允许 value 为 null（上游漏传 tool_use_id 时保持原样），因此不能用 {@code Map.of}。
     */
    public static Map<String, Object> toOpenAiToolResultFields(Object toolUseId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FIELD_TOOL_CALL_ID, toolUseId);
        return fields;
    }

    // ------------------------------------------------------------------
    // 方向二：OpenAI -> Anthropic
    // ------------------------------------------------------------------

    /**
     * OpenAI tools（{@code {type:function, function:{name, description, parameters}}}）-> Anthropic tools。
     */
    public static List<Map<String, Object>> toAnthropicTools(Object tools) {
        List<Map<String, Object>> converted = new ArrayList<>();
        if (!(tools instanceof List<?> toolList) || toolList.isEmpty()) {
            return converted;
        }
        for (Object each : toolList) {
            if (!(each instanceof Map<?, ?> tool) || !(tool.get(FIELD_FUNCTION) instanceof Map<?, ?> function)) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(FIELD_NAME, function.get(FIELD_NAME));
            if (function.get(FIELD_DESCRIPTION) != null) {
                result.put(FIELD_DESCRIPTION, function.get(FIELD_DESCRIPTION));
            }
            result.put(FIELD_INPUT_SCHEMA, function.get(FIELD_PARAMETERS) == null
                    ? Map.of(FIELD_TYPE, "object")
                    : function.get(FIELD_PARAMETERS));
            converted.add(result);
        }
        return converted;
    }

    /**
     * OpenAI tool_choice -> Anthropic tool_choice。
     * <p>
     * 与 {@link #toOpenAiToolChoice(JSONObject)} 互逆；{@code none} 在 Anthropic 侧没有等价物
     * （不传 tools 即可），因此返回 null。
     */
    public static Map<String, Object> toAnthropicToolChoice(Object toolChoice) {
        if (toolChoice instanceof CharSequence text) {
            return switch (text.toString()) {
                case TYPE_AUTO -> Map.of(FIELD_TYPE, TYPE_AUTO);
                case "required", "any" -> Map.of(FIELD_TYPE, "any");
                default -> null;
            };
        }
        if (toolChoice instanceof Map<?, ?> mapValue
                && mapValue.get(FIELD_FUNCTION) instanceof Map<?, ?> function
                && function.get(FIELD_NAME) != null) {
            return Map.of(FIELD_TYPE, TYPE_TOOL_CHOICE, FIELD_NAME, function.get(FIELD_NAME));
        }
        return null;
    }

    /**
     * OpenAI usage -> Anthropic usage。
     */
    public static JSONObject toAnthropicUsage(JSONObject openAiUsage) {
        if (openAiUsage == null || openAiUsage.isEmpty()) {
            return null;
        }
        JSONObject usage = new JSONObject();
        usage.put(FIELD_INPUT_TOKENS, longOrZero(openAiUsage.getLong(FIELD_PROMPT_TOKENS)));
        usage.put(FIELD_OUTPUT_TOKENS, longOrZero(openAiUsage.getLong(FIELD_COMPLETION_TOKENS)));
        return usage;
    }

    /**
     * OpenAI finish_reason -> Anthropic stop_reason。
     * <p>
     * Anthropic 的 stop_reason 不允许为 null（SDK 会当字符串解析），所以兜底 "end_turn"。
     */
    public static String toAnthropicStopReason(String finishReason, boolean hasToolUse) {
        if (hasToolUse || FIELD_TOOL_CALLS.equals(finishReason)) {
            return FIELD_TOOL_USE;
        }
        if (finishReason == null) {
            return "end_turn";
        }
        return switch (finishReason) {
            case "length" -> "max_tokens";
            default -> "end_turn";
        };
    }

    /**
     * Anthropic text 块。
     */
    public static Map<String, Object> toAnthropicTextBlock(Object text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put(FIELD_TYPE, BLOCK_TEXT);
        block.put("text", text);
        return block;
    }

    /**
     * Anthropic tool_use 块。
     */
    public static Map<String, Object> toAnthropicToolUseBlock(Object id, Object name, Object input) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put(FIELD_TYPE, FIELD_TOOL_USE);
        block.put("id", id);
        block.put(FIELD_NAME, name);
        block.put(FIELD_INPUT, input);
        return block;
    }

    /**
     * Anthropic tool_result 块。
     */
    public static Map<String, Object> toAnthropicToolResultBlock(Object toolUseId, Object content) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put(FIELD_TYPE, BLOCK_TOOL_RESULT);
        block.put(FIELD_TOOL_USE_ID, toolUseId);
        block.put(FIELD_CONTENT, content);
        return block;
    }

    /**
     * OpenAI stop（字符串或数组）-> Anthropic stop_sequences（字符串数组）。
     */
    public static List<String> toAnthropicStopSequences(Object stop) {
        if (stop instanceof CharSequence text && StringUtils.hasText(text)) {
            return List.of(text.toString());
        }
        if (stop instanceof Collection<?> stopCollection && !stopCollection.isEmpty()) {
            return stopCollection.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // 扩展字段映射（两个方向的原样取子集 / 改名，镜像成对）
    // ------------------------------------------------------------------

    /**
     * 把内部请求的 extra 字段按 Anthropic 协议取子集写入上游 payload。
     * <p>
     * 只搬语义等价且合法的字段：无差别透传会把 response_format 之类的 OpenAI 专有参数
     * 直接打到上游并触发 400，反而让"透传"变成故障源。
     */
    public static void applyOpenAiExtraToAnthropicPayload(Map<String, Object> payload, Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        copyIfPresent(extra, payload, FIELD_TOP_P);
        copyIfPresent(extra, payload, FIELD_TOP_K);
        copyIfPresent(extra, payload, FIELD_METADATA);
        List<String> stopSequences = toAnthropicStopSequences(extra.get(FIELD_STOP));
        if (!stopSequences.isEmpty()) {
            payload.put(FIELD_STOP_SEQUENCES, stopSequences);
        }
        List<Map<String, Object>> tools = toAnthropicTools(extra.get(FIELD_TOOLS));
        if (!tools.isEmpty()) {
            payload.put(FIELD_TOOLS, tools);
        }
        Map<String, Object> toolChoice = toAnthropicToolChoice(extra.get(FIELD_TOOL_CHOICE));
        if (toolChoice != null) {
            payload.put(FIELD_TOOL_CHOICE, toolChoice);
        }
    }

    /**
     * 把 Anthropic 请求体的扩展字段按 OpenAI 语义写入内部请求的 unmapped。
     * <p>
     * stop_sequences 与 OpenAI 的 stop 语义等价，必须改名；tools/tool_choice 是结构差异，
     * 其余能把语义对齐的直接搬。
     */
    public static void applyAnthropicBodyToOpenAiRequest(AiChatCompletionReqDTO request, JSONObject body) {
        putIfPresent(request, body, FIELD_TOP_P);
        putIfPresent(request, body, FIELD_TOP_K);
        Object stopSequences = body.get(FIELD_STOP_SEQUENCES);
        if (stopSequences != null) {
            request.getUnmapped().put(FIELD_STOP, stopSequences);
        }
        JSONArray tools = body.getJSONArray(FIELD_TOOLS);
        if (tools != null && !tools.isEmpty()) {
            request.getUnmapped().put(FIELD_TOOLS, toOpenAiTools(tools));
        }
        JSONObject toolChoice = body.getJSONObject(FIELD_TOOL_CHOICE);
        if (toolChoice != null) {
            Map<String, Object> converted = toOpenAiToolChoice(toolChoice);
            if (converted != null) {
                request.getUnmapped().put(FIELD_TOOL_CHOICE, converted);
            }
        }
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void putIfPresent(AiChatCompletionReqDTO request, JSONObject body, String field) {
        Object value = body.get(field);
        if (value != null) {
            request.getUnmapped().put(field, value);
        }
    }

    // ------------------------------------------------------------------
    // Anthropic SSE 事件载荷
    // ------------------------------------------------------------------

    /**
     * 构造 Anthropic 事件。
     * <p>
     * 必须写 null：Anthropic 的 {@code stop_reason}/{@code stop_sequence} 在事件里是显式 null 字段，
     * 省略掉官方 SDK 的严格解析会失败。
     */
    public static ServerSentEvent<String> sseEvent(String name, JSONObject payload) {
        return ServerSentEvent.<String>builder()
                .event(name)
                .data(JSON.toJSONString(payload, JSONWriter.Feature.WriteNulls))
                .build();
    }

    public static JSONObject anthropicEvent(String type) {
        JSONObject payload = new JSONObject();
        payload.put(FIELD_TYPE, type);
        return payload;
    }

    public static JSONObject anthropicBlockStart(int index, Object block) {
        JSONObject payload = anthropicEvent("content_block_start");
        payload.put("index", index);
        payload.put("content_block", block);
        return payload;
    }

    public static JSONObject anthropicTextDelta(int index, String text) {
        JSONObject delta = new JSONObject();
        delta.put(FIELD_TYPE, "text_delta");
        delta.put("text", text);
        JSONObject payload = anthropicEvent("content_block_delta");
        payload.put("index", index);
        payload.put("delta", delta);
        return payload;
    }

    public static JSONObject anthropicInputJsonDelta(int index, String partialJson) {
        JSONObject delta = new JSONObject();
        delta.put(FIELD_TYPE, "input_json_delta");
        delta.put("partial_json", partialJson);
        JSONObject payload = anthropicEvent("content_block_delta");
        payload.put("index", index);
        payload.put("delta", delta);
        return payload;
    }

    public static JSONObject anthropicBlockStop(int index) {
        JSONObject payload = anthropicEvent("content_block_stop");
        payload.put("index", index);
        return payload;
    }

    public static JSONObject anthropicMessageDelta(String stopReason, long outputTokens) {
        JSONObject delta = new JSONObject();
        delta.put("stop_reason", stopReason);
        delta.put("stop_sequence", null);
        JSONObject usage = new JSONObject();
        usage.put(FIELD_OUTPUT_TOKENS, outputTokens);
        JSONObject payload = anthropicEvent("message_delta");
        payload.put("delta", delta);
        payload.put("usage", usage);
        return payload;
    }

    /**
     * 把上游错误对象包成 Anthropic 的 error 事件载荷。
     */
    public static JSONObject anthropicError(JSONObject error) {
        JSONObject payload = anthropicEvent("error");
        JSONObject detail = new JSONObject();
        String type = error == null ? null : error.getString(FIELD_TYPE);
        String message = error == null ? null : error.getString("message");
        detail.put(FIELD_TYPE, type == null ? "api_error" : type);
        detail.put("message", message == null ? "upstream error" : message);
        payload.put("error", detail);
        return payload;
    }

    // ------------------------------------------------------------------
    // 两侧共用的小工具
    // ------------------------------------------------------------------

    /**
     * 解析 tool_call 的 arguments（可能是 Map，也可能是 JSON 字符串）。
     */
    public static Object parseArguments(Object arguments) {
        if (arguments == null) {
            return new JSONObject();
        }
        if (arguments instanceof Map<?, ?> mapValue) {
            return mapValue;
        }
        String raw = String.valueOf(arguments).trim();
        if (raw.isEmpty()) {
            return new JSONObject();
        }
        try {
            return JSON.parse(raw);
        } catch (Exception ex) {
            log.debug("tool_call arguments 不是合法 JSON，按空对象处理: {}", raw);
            return new JSONObject();
        }
    }

    /**
     * 优先回客户端请求的模型名，避免把上游真实模型名泄漏给客户端。
     */
    public static String resolveModel(String preferred, String fallback) {
        if (StringUtils.hasText(preferred)) {
            return preferred;
        }
        return StringUtils.hasText(fallback) ? fallback : "unknown";
    }

    public static long longOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
