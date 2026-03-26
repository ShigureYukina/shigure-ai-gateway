package com.nageoffer.shortlink.aigateway.dto.req;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

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
}
