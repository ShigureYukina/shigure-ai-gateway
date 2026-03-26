package com.nageoffer.shortlink.aigateway.plugin.impl;

import com.nageoffer.shortlink.aigateway.plugin.AiGatewayPlugin;
import com.nageoffer.shortlink.aigateway.plugin.PluginResponseContext;
import org.springframework.stereotype.Component;

@Component
public class ResponseTracePlugin implements AiGatewayPlugin {

    @Override
    public String pluginName() {
        return "response-trace-plugin";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String afterResponse(PluginResponseContext context) {
        String body = context.getResponseBody();
        if (body == null) {
            return null;
        }
        return body;
    }
}
