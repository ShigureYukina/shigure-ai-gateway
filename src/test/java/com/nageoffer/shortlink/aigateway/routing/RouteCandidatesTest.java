package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class RouteCandidatesTest {

    @Test
    void shouldOrderPriorityFirstThenRemainingConfiguredProviders() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("fallback-only");
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");
        properties.getUpstream().getProviderBaseUrl().put("fallback-only", "https://fallback.example.com");
        properties.getRouting().setProviderPriority(List.of("claude", "openai"));

        // 优先级列表只影响顺序，不该把没进优先级的已配置通道漏掉
        Assertions.assertEquals(List.of("claude", "openai", "fallback-only"),
                List.copyOf(RouteCandidates.configured(properties)));
    }

    @Test
    void shouldDropProvidersWithoutBaseUrlEvenIfTheyAreInPriority() {
        // baseUrl 为空的通道连地址都拼不出来，留在回退链里只会多一次注定失败的尝试
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("broken", "   ");
        properties.getRouting().setProviderPriority(List.of("broken", "ghost"));

        Assertions.assertEquals(List.of("openai"), List.copyOf(RouteCandidates.configured(properties)));
    }

    @Test
    void shouldNotDuplicateProviderListedInBothPriorityAndBaseUrl() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getRouting().setProviderPriority(List.of("openai", "openai"));

        Assertions.assertEquals(List.of("openai"), List.copyOf(RouteCandidates.configured(properties)));
    }

    @Test
    void shouldReturnEmptyWhenProviderBaseUrlMapIsMissing() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setProviderBaseUrl(null);

        Assertions.assertTrue(RouteCandidates.configured(properties).isEmpty());
    }

    @Test
    void shouldFilterOutChannelsRejectedByHealthView() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");
        properties.getRouting().setProviderPriority(List.of("openai", "claude"));

        Assertions.assertEquals(List.of("openai"),
                List.copyOf(RouteCandidates.configured(properties, provider -> !"claude".equals(provider))));
    }

    @Test
    void shouldBehaveLikeTheOldOverloadWhenViewIsNull() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");

        // view 为 null 等价于"不做健康过滤"：既有的只关心配置的调用方仍可以这么传
        Assertions.assertEquals(RouteCandidates.configured(properties),
                RouteCandidates.configured(properties, null));
        Assertions.assertEquals(List.of("openai", "claude"),
                List.copyOf(RouteCandidates.configured(properties, ChannelHealthView.allowAll())));
    }
}
