package com.nageoffer.shortlink.aigateway.observability;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ModelMetricsSnapshot {

    private String model;

    private Long callCount;

    private Double successRate;

    private Long p95LatencyMillis;

    private Double totalCost;
}
