package com.nageoffer.shortlink.aigateway.dto.req;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话消息。
 * <p>
 * {@code content} 声明为 {@link Object}：既支持普通字符串，也支持多模态的内容块数组。
 * 未被显式建模的字段（如 {@code tool_calls} / {@code tool_call_id} / {@code name}）
 * 通过 {@code unmapped} 原样透传给上游，避免网关成为字段瓶颈。
 */
@Data
@Schema(description = "对话消息")
public class AiChatCompletionMessage {

    @Schema(description = "消息角色", example = "user")
    @NotBlank(message = "role不能为空")
    private String role;

    @Schema(description = "消息内容，字符串或多模态内容块数组")
    private Object content;

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
