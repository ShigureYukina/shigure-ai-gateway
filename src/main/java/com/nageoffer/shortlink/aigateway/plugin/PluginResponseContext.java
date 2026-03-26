package com.nageoffer.shortlink.aigateway.plugin;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class PluginResponseContext {

    private String provider;

    private String model;

    private String requestId;

    private String responseBody;

    private Long latencyMillis;

    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();
}
