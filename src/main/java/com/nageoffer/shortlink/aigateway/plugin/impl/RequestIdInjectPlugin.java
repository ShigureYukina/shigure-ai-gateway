package com.nageoffer.shortlink.aigateway.plugin.impl;

import com.nageoffer.shortlink.aigateway.plugin.AiGatewayPlugin;
import com.nageoffer.shortlink.aigateway.plugin.PluginRequestContext;
import org.springframework.stereotype.Component;

@Component
public class RequestIdInjectPlugin implements AiGatewayPlugin {

    @Override
    public String pluginName() {
        return "request-id-inject-plugin";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public void beforeRequest(PluginRequestContext context) {
        context.getHeaders().set("X-Request-Id", context.getRequestId());
    }
}
