package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;

class ProviderRoutingServiceTest {

    @Test
    void shouldResolveAliasProviderAndModel() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().getDefaultProvider();
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");
        properties.getUpstream().getModelAlias().put("claude-compat", "claude:claude-3-5-sonnet-latest");

        ProviderRoutingService service = new ProviderRoutingService(properties);
        AiRoutingResult result = service.resolve("claude-compat", new HttpHeaders());

        Assertions.assertEquals("claude", result.getProvider());
        Assertions.assertEquals("claude-3-5-sonnet-latest", result.getProviderModel());
        Assertions.assertTrue(result.getUpstreamUri().contains("/v1/chat/completions"));
    }

    @Test
    void shouldReturnFallbackCandidatesWhenFallbackEnabled() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("openai");
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");
        properties.getRouting().setFallbackEnabled(true);
        properties.getRouting().setProviderPriority(java.util.List.of("openai", "claude"));

        ProviderRoutingService service = new ProviderRoutingService(properties);
        AiRoutingResult result = service.resolve("gpt-4o-mini", new HttpHeaders());

        Assertions.assertEquals("openai", result.getProvider());
        Assertions.assertNotNull(result.getFallbackCandidates());
        Assertions.assertEquals(1, result.getFallbackCandidates().size());
        Assertions.assertEquals("claude", result.getFallbackCandidates().get(0).getProvider());
        Assertions.assertTrue(result.getFallbackCandidates().get(0).getUpstreamUri().contains("/v1/chat/completions"));
    }

    @Test
    void shouldThrowWhenProviderBaseUrlMissing() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("openai");

        ProviderRoutingService service = new ProviderRoutingService(properties);

        Assertions.assertThrows(
                com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException.class,
                () -> service.resolve("gpt-4o-mini", new HttpHeaders())
        );
    }

    @Test
    void shouldPreferHeaderProviderWhenPresent() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("openai");
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");

        ProviderRoutingService service = new ProviderRoutingService(properties);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-AI-Provider", "claude");
        AiRoutingResult result = service.resolve("gpt-4o-mini", headers);

        Assertions.assertEquals("claude", result.getProvider());
        Assertions.assertEquals("header", result.getRouteSource());
        Assertions.assertTrue(result.getUpstreamUri().contains("api.anthropic.com"));
    }

    @Test
    void shouldApplyModelAliasWithoutChangingProvider() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("openai");
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com/");
        properties.getUpstream().getModelAlias().put("mini", "gpt-4o-mini");

        ProviderRoutingService service = new ProviderRoutingService(properties);
        AiRoutingResult result = service.resolve("mini", new HttpHeaders());

        Assertions.assertEquals("openai", result.getProvider());
        Assertions.assertEquals("gpt-4o-mini", result.getProviderModel());
        Assertions.assertEquals("https://api.openai.com/v1/chat/completions", result.getUpstreamUri());
    }

    @Test
    void shouldRouteToAbProviderWhenAbEnabledAndPercentageMatches() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("openai");
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");
        properties.getRouting().setAbEnabled(true);
        properties.getRouting().setAbProvider("claude");
        properties.getRouting().setAbPercentage(100);

        ProviderRoutingService service = new ProviderRoutingService(properties);
        AiRoutingResult result = service.resolve("gpt-4o-mini", new HttpHeaders());

        Assertions.assertEquals("claude", result.getProvider());
        Assertions.assertEquals(Boolean.TRUE, result.getAbHit());
        Assertions.assertEquals("ab", result.getRouteSource());
    }

    @Test
    void shouldNormalizeRoutingConfigUpdateAndClampAbPercentage() {
        AiGatewayProperties properties = new AiGatewayProperties();
        ProviderRoutingService service = new ProviderRoutingService(properties);

        Map<String, Object> result = service.updateRoutingConfig(Map.of(
                "fallbackEnabled", true,
                "providerPriority", List.of("openai", "claude", "openai", " "),
                "abEnabled", true,
                "abProvider", " claude ",
                "abPercentage", 999,
                "providerBaseUrl", Map.of("openai", " https://api.openai.com "),
                "modelAlias", Map.of("mini", "gpt-4o-mini")
        ));

        Assertions.assertEquals(true, result.get("fallbackEnabled"));
        Assertions.assertEquals(true, result.get("abEnabled"));
        Assertions.assertEquals("claude", result.get("abProvider"));
        Assertions.assertEquals(100, result.get("abPercentage"));
        Assertions.assertEquals(List.of("openai", "claude"), result.get("providerPriority"));
        Assertions.assertEquals("https://api.openai.com", ((Map<?, ?>) result.get("providerBaseUrl")).get("openai"));
    }
}
