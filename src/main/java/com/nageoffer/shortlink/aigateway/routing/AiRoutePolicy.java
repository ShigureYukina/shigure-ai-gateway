package com.nageoffer.shortlink.aigateway.routing;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.util.Set;

@Data
@Builder
public class AiRoutePolicy {

    private Duration requestTimeout;

    private Integer maxRetries;

    private Set<Integer> retryStatusCodes;
}
