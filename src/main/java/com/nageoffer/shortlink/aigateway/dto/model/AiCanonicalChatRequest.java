package com.nageoffer.shortlink.aigateway.dto.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class AiCanonicalChatRequest {

    private String provider;

    private String clientModel;

    private String providerModel;

    private Boolean stream;

    private Double temperature;

    private Integer maxTokens;

    private List<Map<String, String>> messages;

    private Map<String, Object> metadata;
}
