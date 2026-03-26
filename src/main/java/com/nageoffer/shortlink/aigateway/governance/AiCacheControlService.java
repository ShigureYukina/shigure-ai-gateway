package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiCacheControlService {

    private final AiGatewayProperties properties;

    public boolean enabledForRequest(HttpHeaders headers, boolean stream) {
        if (stream || !properties.getCache().isEnabled()) {
            return false;
        }
        String bypass = headers.getFirst("X-Cache-Bypass");
        return bypass == null || !"true".equalsIgnoreCase(bypass);
    }
}
