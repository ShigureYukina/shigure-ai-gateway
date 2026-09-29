package com.nageoffer.shortlink.aigateway.dto.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一错误响应。
 * <p>
 * 顶层 {@code status / message} 为网关历史契约，保留以兼容既有调用方；
 * {@code error} 为 OpenAI 兼容错误对象，官方 SDK 读取的是这一层。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "OpenAI 兼容错误响应")
public class AiGatewayErrorRespDTO {

    @Schema(description = "HTTP 状态码", example = "429")
    private Integer status;

    @Schema(description = "错误描述（历史字段，等同于 error.message）")
    private String message;

    @Schema(description = "OpenAI 兼容错误对象")
    private ErrorDetail error;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "OpenAI 兼容错误对象")
    public static class ErrorDetail {

        @Schema(description = "错误描述")
        private String message;

        @Schema(description = "错误分类，如 invalid_request_error / rate_limit_error / api_error")
        private String type;

        @Schema(description = "机器可读错误码")
        private String code;

        @Schema(description = "出错参数名，无则为 null")
        private String param;
    }
}
