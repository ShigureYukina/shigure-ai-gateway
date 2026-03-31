package com.nageoffer.shortlink.aigateway.routing;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ProviderHealthScore {

    private String provider;

    private String model;

    private Integer healthScore;

    private Long avgLatencyMillis;

    private Double successRate;

    private Double costPerCall;

    private Instant lastUpdated;
}
