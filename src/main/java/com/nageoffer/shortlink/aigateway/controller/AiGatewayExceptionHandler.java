package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.dto.resp.AiGatewayErrorRespDTO;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayUpstreamException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AiGatewayExceptionHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<AiGatewayErrorRespDTO> handleValidationException(WebExchangeBindException ex) {
        FieldError fieldError = ex.getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : AiGatewayErrorCode.BAD_REQUEST.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(AiGatewayErrorRespDTO.builder()
                        .status(HttpStatus.BAD_REQUEST.value())
                        .message(message)
                        .build());
    }

    @ExceptionHandler(AiGatewayClientException.class)
    public ResponseEntity<AiGatewayErrorRespDTO> handleClientException(AiGatewayClientException ex) {
        return ResponseEntity.status(ex.getErrorCode().getStatus())
                .body(AiGatewayErrorRespDTO.builder()
                        .status(ex.getErrorCode().getStatus())
                        .message(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(AiGatewayUpstreamException.class)
    public ResponseEntity<AiGatewayErrorRespDTO> handleUpstreamException(AiGatewayUpstreamException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(AiGatewayErrorRespDTO.builder()
                        .status(HttpStatus.BAD_GATEWAY.value())
                        .message(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AiGatewayErrorRespDTO> handleGenericException(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AiGatewayErrorRespDTO.builder()
                        .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .message(ex.getMessage())
                        .build());
    }
}
