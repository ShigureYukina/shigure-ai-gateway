package com.nageoffer.shortlink.aigateway.exception;

import lombok.Getter;

@Getter
public class AiGatewayUpstreamException extends RuntimeException {

    private final Integer status;

    private final boolean retriable;

    public AiGatewayUpstreamException(Integer status, String message, boolean retriable) {
        super(message);
        this.status = status;
        this.retriable = retriable;
    }
}
