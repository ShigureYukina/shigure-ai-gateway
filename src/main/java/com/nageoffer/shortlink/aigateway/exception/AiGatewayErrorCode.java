package com.nageoffer.shortlink.aigateway.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AiGatewayErrorCode {

    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权访问"),
    FORBIDDEN(403, "无权限执行该操作"),
    QUOTA_EXCEEDED(429, "配额不足或已超限"),
    PROVIDER_NOT_CONFIGURED(400, "Provider未配置"),
    PROVIDER_ADAPTER_NOT_FOUND(400, "Provider适配器不存在"),
    UPSTREAM_CALL_FAILED(502, "上游调用失败"),
    UPSTREAM_RETRY_EXHAUSTED(504, "上游重试已耗尽");

    private final Integer status;

    private final String message;
}
