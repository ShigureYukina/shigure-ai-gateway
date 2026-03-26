package com.nageoffer.shortlink.aigateway.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "控制台登录请求")
public class ConsoleLoginReqDTO {

    @NotBlank(message = "username不能为空")
    @Schema(description = "用户名", example = "admin")
    private String username;

    @NotBlank(message = "password不能为空")
    @Schema(description = "密码", example = "admin123456")
    private String password;
}
