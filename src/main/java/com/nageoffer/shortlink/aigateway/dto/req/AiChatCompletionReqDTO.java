package com.nageoffer.shortlink.aigateway.dto.req;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容聊天补全请求。
 * <p>
 * 只有网关治理需要用到的字段（模型、消息、流式、采样参数、配额预估上限）被显式建模；
 * {@code tools} / {@code response_format} / {@code top_p} / {@code seed} 等其余字段
 * 统一收进 {@code unmapped}，由适配器合并后按上游协议处理，避免每新增一个上游参数就改一次 DTO。
 */
@Data
@Schema(description = "OpenAI 兼容聊天补全请求")
public class AiChatCompletionReqDTO {

    @Schema(description = "客户端模型名（支持 alias）", example = "gpt-4o-mini-compatible")
    @NotBlank(message = "model不能为空")
    private String model;

    @ArraySchema(schema = @Schema(implementation = AiChatCompletionMessage.class), arraySchema = @Schema(description = "对话消息列表"))
    @NotEmpty(message = "messages不能为空")
    @Valid
    private List<AiChatCompletionMessage> messages;

    @Schema(description = "是否流式返回", example = "false")
    private Boolean stream = Boolean.FALSE;

    @Schema(description = "采样温度", example = "0.7")
    private Double temperature;

    @Schema(description = "最大输出 token", example = "512")
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @Schema(description = "扩展元数据")
    private Map<String, Object> metadata;

    @JsonIgnore
    @Schema(hidden = true)
    private Map<String, Object> unmapped = new LinkedHashMap<>();

    @JsonAnySetter
    public void putUnmapped(String name, Object value) {
        unmapped.put(name, value);
    }

    @JsonAnyGetter
    public Map<String, Object> unmappedFields() {
        return unmapped;
    }
}
