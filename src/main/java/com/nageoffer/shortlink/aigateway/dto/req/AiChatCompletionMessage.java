package com.nageoffer.shortlink.aigateway.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "对话消息")
public class AiChatCompletionMessage {

    @Schema(description = "消息角色", example = "user")
    @NotBlank(message = "role不能为空")
    private String role;

    @Schema(description = "消息内容", example = "请介绍一下短链接系统")
    @NotBlank(message = "content不能为空")
    private String content;
}
