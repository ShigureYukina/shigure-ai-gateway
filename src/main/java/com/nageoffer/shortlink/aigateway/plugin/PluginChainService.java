package com.nageoffer.shortlink.aigateway.plugin;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PluginChainService {

    private final List<AiGatewayPlugin> pluginList;

    private final AiGatewayProperties properties;

    public void executeBeforeRequest(PluginRequestContext context) {
        for (AiGatewayPlugin each : resolvePlugins(context.getProvider(), context.getModel())) {
            each.beforeRequest(context);
        }
    }

    public String executeAfterResponse(PluginResponseContext context) {
        String body = context.getResponseBody();
        List<AiGatewayPlugin> plugins = resolvePlugins(context.getProvider(), context.getModel());
        for (int i = plugins.size() - 1; i >= 0; i--) {
            PluginResponseContext currentContext = PluginResponseContext.builder()
                    .provider(context.getProvider())
                    .model(context.getModel())
                    .requestId(context.getRequestId())
                    .latencyMillis(context.getLatencyMillis())
                    .attributes(context.getAttributes())
                    .responseBody(body)
                    .build();
            body = plugins.get(i).afterResponse(currentContext);
        }
        return body;
    }

    private List<AiGatewayPlugin> resolvePlugins(String provider, String model) {
        if (!properties.getPlugin().isEnabled()) {
            return List.of();
        }
        Map<String, AiGatewayPlugin> pluginMap = pluginList.stream().collect(Collectors.toMap(AiGatewayPlugin::pluginName, each -> each));
        Set<String> enabledNames = properties.getPlugin().getPluginEnabledMap().entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        List<String> merged = new ArrayList<>();
        merged.addAll(properties.getPlugin().getGlobalPlugins());
        Map<String, List<String>> routePlugins = properties.getPlugin().getRoutePlugins();
        merged.addAll(routePlugins.getOrDefault(provider, List.of()));
        merged.addAll(routePlugins.getOrDefault(provider + ":" + model, List.of()));

        List<AiGatewayPlugin> resolved = new ArrayList<>();
        Map<String, Boolean> deduplicate = new HashMap<>();
        for (String name : merged) {
            if (deduplicate.containsKey(name) || !enabledNames.contains(name)) {
                continue;
            }
            AiGatewayPlugin plugin = pluginMap.get(name);
            if (plugin != null) {
                resolved.add(plugin);
                deduplicate.put(name, true);
            }
        }
        resolved.sort(Comparator.comparingInt(AiGatewayPlugin::order));
        return resolved;
    }
}
