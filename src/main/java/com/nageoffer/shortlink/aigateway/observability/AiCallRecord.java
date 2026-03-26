package com.nageoffer.shortlink.aigateway.observability;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiCallRecord {

    private String requestId;

    private String provider;

    private String model;

    private String tenantId;

    private String appId;

    private String keyId;

    private Long tokenIn;

    private Long tokenOut;

    private Long latencyMillis;

    private Integer status;

    private Double cost;

    private Boolean cacheHit;

    private Long timestamp;
}
