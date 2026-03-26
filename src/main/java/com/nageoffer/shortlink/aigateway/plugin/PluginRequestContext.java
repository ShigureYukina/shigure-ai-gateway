package com.nageoffer.shortlink.aigateway.plugin;

import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import lombok.Builder;
import lombok.Data;
import org.springframework.http.HttpHeaders;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class PluginRequestContext {

    private String provider;

    private String model;

    private String requestId;

    private AiChatCompletionReqDTO request;

    private HttpHeaders headers;

    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();
}
