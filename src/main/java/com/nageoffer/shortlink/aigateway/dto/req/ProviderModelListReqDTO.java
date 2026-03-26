package com.nageoffer.shortlink.aigateway.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "按 API 地址与 Key 拉取模型列表请求")
public class ProviderModelListReqDTO {

    @Schema(description = "上游 API Base URL", example = "https://api.openai.com")
    @NotBlank(message = "baseUrl不能为空")
    private String baseUrl;

    @Schema(description = "上游 API Key", example = "sk-xxx")
    @NotBlank(message = "apiKey不能为空")
    private String apiKey;
}
