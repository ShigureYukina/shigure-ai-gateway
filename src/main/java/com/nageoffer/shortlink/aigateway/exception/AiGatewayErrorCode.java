package com.nageoffer.shortlink.aigateway.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 网关错误码。
 * <p>
 * 除 HTTP 状态码外，同时携带面向 OpenAI 兼容客户端的 {@code type} 与机器可读的 {@code code}，
 * 使官方 SDK 能按 {@code error.type / error.code} 正常分类与重试。
 */
@Getter
@RequiredArgsConstructor
public enum AiGatewayErrorCode {

    BAD_REQUEST(400, "请求参数错误", "invalid_request_error", "invalid_request"),
    UNAUTHORIZED(401, "未授权访问", "invalid_request_error", "invalid_api_key"),
    FORBIDDEN(403, "无权限执行该操作", "permission_error", "permission_denied"),
    QUOTA_EXCEEDED(429, "配额不足或已超限", "rate_limit_error", "insufficient_quota"),
    PROVIDER_NOT_CONFIGURED(400, "Provider未配置", "invalid_request_error", "provider_not_configured"),
    PROVIDER_ADAPTER_NOT_FOUND(400, "Provider适配器不存在", "invalid_request_error", "provider_adapter_not_found"),
    UPSTREAM_CREDENTIAL_MISSING(502, "未配置上游凭证", "api_error", "upstream_credential_missing"),
    UPSTREAM_CALL_FAILED(502, "上游调用失败", "api_error", "upstream_error"),
    UPSTREAM_RETRY_EXHAUSTED(504, "上游重试已耗尽", "api_error", "upstream_retry_exhausted"),
    PROVIDER_RATE_LIMITED(429, "上游渠道已达调用频率上限", "rate_limit_error", "provider_rate_limited"),
    /**
     * 调用方<b>显式指定</b>的渠道被探测判为不可用或被人为禁用。
     * <p>
     * 用 503 而不是 400：这不是请求写错了，而是"此刻没有这个能力"，客户端重试是合理动作。
     * 刻意不在这种情形下静默换一个渠道 —— 显式点名通常用于对比测试或定向排查，
     * 悄悄换掉会让调用方拿到的结论完全错误。
     */
    PROVIDER_DISABLED(503, "渠道当前不可用", "api_error", "provider_disabled"),
    /**
     * 库里的密文解不开（主密钥换了、行被改坏）。
     * <p>
     * 刻意不降级成"当明文用"：那会把一条坏行变成"古怪的 apiKey"，表现为上游 401 且无从定位。
     * 宁可在这里明确失败。
     */
    SECRET_DECRYPT_FAILED(500, "密钥解密失败", "api_error", "secret_decrypt_failed");

    private final Integer status;

    private final String message;

    /**
     * OpenAI 兼容错误分类。
     */
    private final String type;

    /**
     * 机器可读错误码。
     */
    private final String code;
}
