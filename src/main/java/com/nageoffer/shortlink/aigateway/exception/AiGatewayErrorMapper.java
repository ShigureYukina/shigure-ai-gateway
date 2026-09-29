package com.nageoffer.shortlink.aigateway.exception;

import com.alibaba.fastjson2.JSONObject;
import com.nageoffer.shortlink.aigateway.dto.resp.AiGatewayErrorRespDTO;

/**
 * 异常到错误响应的统一映射。
 * <p>
 * 做成无状态工具类而非 Spring Bean，原因有两个：
 * 1) 流式链路在 Flux 内部映射错误帧，拿不到 MVC 的异常处理器；
 * 2) 异常处理器本身无需任何依赖，便于单测直接 new。
 */
public final class AiGatewayErrorMapper {

    private static final String TYPE_API_ERROR = "api_error";

    private static final String CODE_UPSTREAM_ERROR = "upstream_error";

    private static final String CODE_INTERNAL_ERROR = "internal_error";

    private AiGatewayErrorMapper() {
    }

    /**
     * 解析最终返回给客户端的 HTTP 状态码。
     * <p>
     * 上游 4xx 属于调用方问题，原样透传让客户端看到真实原因；
     * 上游 5xx 统一收敛为 502，避免把上游内部状态泄漏成网关自身状态。
     */
    public static int statusOf(Throwable throwable) {
        if (throwable instanceof AiGatewayClientException clientException) {
            return clientException.getErrorCode().getStatus();
        }
        if (throwable instanceof AiGatewayUpstreamException upstreamException) {
            Integer upstreamStatus = upstreamException.getStatus();
            if (upstreamStatus != null && upstreamStatus >= 400 && upstreamStatus < 500) {
                return upstreamStatus;
            }
            return 502;
        }
        return 500;
    }

    public static AiGatewayErrorRespDTO toResponse(Throwable throwable) {
        AiGatewayErrorRespDTO.ErrorDetail.ErrorDetailBuilder detail = AiGatewayErrorRespDTO.ErrorDetail.builder()
                .message(messageOf(throwable))
                .param(null);
        String type;
        String code;
        if (throwable instanceof AiGatewayClientException clientException) {
            AiGatewayErrorCode errorCode = clientException.getErrorCode();
            type = errorCode.getType();
            code = errorCode.getCode();
        } else if (throwable instanceof AiGatewayUpstreamException) {
            type = TYPE_API_ERROR;
            code = CODE_UPSTREAM_ERROR;
        } else {
            type = TYPE_API_ERROR;
            code = CODE_INTERNAL_ERROR;
        }
        return AiGatewayErrorRespDTO.builder()
                .status(statusOf(throwable))
                .message(detail.build().getMessage())
                .error(AiGatewayErrorRespDTO.ErrorDetail.builder()
                        .message(messageOf(throwable))
                        .type(type)
                        .code(code)
                        .param(null)
                        .build())
                .build();
    }

    /**
     * 构造流式链路中的错误帧负载。
     * <p>
     * 只输出 OpenAI 兼容的 {@code error} 对象，不含网关历史字段：
     * 流式客户端按 chunk 解析，多余字段没有意义。
     */
    public static String toErrorFramePayload(Throwable throwable) {
        AiGatewayErrorRespDTO response = toResponse(throwable);
        JSONObject error = new JSONObject();
        error.put("message", response.getError().getMessage());
        error.put("type", response.getError().getType());
        error.put("code", response.getError().getCode());
        error.put("param", null);
        JSONObject frame = new JSONObject();
        frame.put("error", error);
        return frame.toJSONString();
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? TYPE_API_ERROR : message;
    }
}
