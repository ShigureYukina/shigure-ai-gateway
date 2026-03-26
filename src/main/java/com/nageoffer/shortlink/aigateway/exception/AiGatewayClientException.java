package com.nageoffer.shortlink.aigateway.exception;

import lombok.Getter;

@Getter
public class AiGatewayClientException extends RuntimeException {

    private final AiGatewayErrorCode errorCode;

    public AiGatewayClientException(AiGatewayErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
