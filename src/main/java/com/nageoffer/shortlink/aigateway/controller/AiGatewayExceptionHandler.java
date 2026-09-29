package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.resp.AiGatewayErrorRespDTO;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorMapper;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayUpstreamException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

import java.util.UUID;

/**
 * 统一异常处理。
 * <p>
 * 所有错误响应都带 OpenAI 兼容的 {@code error} 对象，并补齐 {@code X-Request-Id}
 * 与配额超限时的 {@code Retry-After}，便于客户端排障与退避重试。
 */
@Slf4j
@RestControllerAdvice
public class AiGatewayExceptionHandler {

    private final AiGatewayProperties properties;

    public AiGatewayExceptionHandler(AiGatewayProperties properties) {
        this.properties = properties;
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<AiGatewayErrorRespDTO> handleValidationException(WebExchangeBindException ex, ServerWebExchange exchange) {
        FieldError fieldError = ex.getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : AiGatewayErrorCode.BAD_REQUEST.getMessage();
        AiGatewayErrorCode errorCode = AiGatewayErrorCode.BAD_REQUEST;
        AiGatewayErrorRespDTO body = AiGatewayErrorRespDTO.builder()
                .status(errorCode.getStatus())
                .message(message)
                .error(AiGatewayErrorRespDTO.ErrorDetail.builder()
                        .message(message)
                        .type(errorCode.getType())
                        .code(errorCode.getCode())
                        .param(fieldError == null ? null : fieldError.getField())
                        .build())
                .build();
        return ResponseEntity.status(errorCode.getStatus())
                .headers(responseHeaders(exchange))
                .body(body);
    }

    @ExceptionHandler(AiGatewayClientException.class)
    public ResponseEntity<AiGatewayErrorRespDTO> handleClientException(AiGatewayClientException ex, ServerWebExchange exchange) {
        return buildResponse(ex, exchange);
    }

    @ExceptionHandler(AiGatewayUpstreamException.class)
    public ResponseEntity<AiGatewayErrorRespDTO> handleUpstreamException(AiGatewayUpstreamException ex, ServerWebExchange exchange) {
        return buildResponse(ex, exchange);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AiGatewayErrorRespDTO> handleGenericException(Exception ex, ServerWebExchange exchange) {
        log.error("unhandled gateway exception, requestId={}", resolveRequestId(exchange), ex);
        return buildResponse(ex, exchange);
    }

    private ResponseEntity<AiGatewayErrorRespDTO> buildResponse(Throwable throwable, ServerWebExchange exchange) {
        long retryAfterSeconds = throwable instanceof AiGatewayClientException clientException
                && AiGatewayErrorCode.QUOTA_EXCEEDED == clientException.getErrorCode()
                ? resolveRetryAfterSeconds()
                : 0L;
        AiGatewayErrorRespDTO body = AiGatewayErrorMapper.toResponse(throwable);
        HttpHeaders headers = responseHeaders(exchange);
        if (retryAfterSeconds > 0) {
            headers.set("Retry-After", String.valueOf(retryAfterSeconds));
        }
        return ResponseEntity.status(body.getStatus())
                .headers(headers)
                .body(body);
    }

    private HttpHeaders responseHeaders(ServerWebExchange exchange) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", resolveRequestId(exchange));
        return headers;
    }

    private String resolveRequestId(ServerWebExchange exchange) {
        if (exchange == null) {
            return UUID.randomUUID().toString();
        }
        String requestId = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    private long resolveRetryAfterSeconds() {
        Long configured = properties.getRateLimit().getQuotaRetryAfterSeconds();
        return configured == null || configured <= 0 ? 60L : configured;
    }
}
