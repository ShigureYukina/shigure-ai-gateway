package com.nageoffer.shortlink.aigateway.plugin;

public interface AiGatewayPlugin {

    String pluginName();

    int order();

    default void beforeRequest(PluginRequestContext context) {
    }

    default String afterResponse(PluginResponseContext context) {
        return context.getResponseBody();
    }
}
