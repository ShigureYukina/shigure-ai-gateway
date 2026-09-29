package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTenantProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import com.nageoffer.shortlink.aigateway.tenant.TenantModelPolicyService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;

import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class RoutingPlanResolverTest {

    private static final TenantContext TENANT_A = new TenantContext("tenant-a", "app-a", "key-a");

    @Test
    void shouldApplyTenantMappingBeforeDecidingRoute() {
        AiGatewayProperties properties = tenantProperties();
        ProviderRoutingService routing = Mockito.mock(ProviderRoutingService.class);
        Mockito.when(routing.resolve(eq("gpt-4o-mini"), any())).thenReturn(route("openai", "gpt-4o-mini"));

        RoutingPlanResolver resolver = resolver(properties, routing);

        RoutingPlanResolver.RoutingPlan plan = resolver.resolve(TENANT_A, "gpt-4o", new HttpHeaders());

        Assertions.assertEquals("gpt-4o-mini", plan.effectiveModel());
        Assertions.assertEquals("openai", plan.routing().getProvider());
        // 顺序不可反：先定模型再定通道，否则会按 gpt-4o 选出一个承载不了它的渠道
        Mockito.verify(routing).resolve(eq("gpt-4o-mini"), any());
    }

    @Test
    void shouldSkipTenantPolicyWhenNoTenantContext() {
        AiGatewayProperties properties = tenantProperties();
        ProviderRoutingService routing = Mockito.mock(ProviderRoutingService.class);
        Mockito.when(routing.resolve(eq("gpt-4o"), any())).thenReturn(route("openai", "gpt-4o"));
        TenantModelPolicyService tenantPolicy = Mockito.mock(TenantModelPolicyService.class);

        RoutingPlanResolver resolver = new RoutingPlanResolver(properties, routing, tenantPolicy);

        RoutingPlanResolver.RoutingPlan plan = resolver.resolve(null, "gpt-4o", new HttpHeaders());

        Assertions.assertEquals("gpt-4o", plan.effectiveModel());
        Mockito.verifyNoInteractions(tenantPolicy);
    }

    @Test
    void shouldRejectDisallowedModelBeforeRouting() {
        AiGatewayProperties properties = tenantProperties();
        properties.getTenant().getModelPolicies().get("tenant-a").setAllowedModels(Set.of("claude-3-5-sonnet-latest"));
        ProviderRoutingService routing = Mockito.mock(ProviderRoutingService.class);

        RoutingPlanResolver resolver = resolver(properties, routing);

        AiGatewayClientException exception = Assertions.assertThrows(AiGatewayClientException.class,
                () -> resolver.resolve(TENANT_A, "gpt-4o", new HttpHeaders()));

        Assertions.assertEquals(AiGatewayErrorCode.FORBIDDEN, exception.getErrorCode());
        Mockito.verifyNoInteractions(routing);
    }

    @Test
    void shouldPreviewThroughTheSamePathAsRealTraffic() {
        AiGatewayProperties properties = tenantProperties();
        ProviderRoutingService routing = Mockito.mock(ProviderRoutingService.class);
        Mockito.when(routing.resolve(anyString(), any())).thenAnswer(invocation -> {
            String model = invocation.getArgument(0);
            return route("openai", model);
        });

        RoutingPlanResolver resolver = resolver(properties, routing);
        HttpHeaders headers = new HttpHeaders();

        // 不带租户：通道维度预览，模型名原样
        Map<String, Object> channelScoped = resolver.preview("gpt-4o", headers, null);
        Assertions.assertEquals(false, channelScoped.get("tenantScoped"));
        Assertions.assertEquals("gpt-4o", channelScoped.get("model"));
        Assertions.assertEquals("gpt-4o", channelScoped.get("providerModel"));

        // 带租户：与真实链路一致，先做租户映射
        Map<String, Object> tenantScoped = resolver.preview("gpt-4o", headers, "tenant-a");
        Assertions.assertEquals(true, tenantScoped.get("tenantScoped"));
        Assertions.assertEquals("gpt-4o-mini", tenantScoped.get("model"));
        Assertions.assertEquals("gpt-4o-mini", tenantScoped.get("providerModel"));
        Assertions.assertEquals("openai", tenantScoped.get("provider"));
    }

    @Test
    void shouldMarkTenantScopedEvenWhenTenantHasNoPolicy() {
        AiGatewayProperties properties = tenantProperties();
        ProviderRoutingService routing = Mockito.mock(ProviderRoutingService.class);
        Mockito.when(routing.resolve(anyString(), any())).thenAnswer(invocation -> {
            String model = invocation.getArgument(0);
            return route("openai", model);
        });

        // 租户没配策略时模型名不变，但"查过租户策略"这件事必须如实标出来，
        // 否则运维会以为是预览没带租户视角
        Map<String, Object> preview = resolver(properties, routing).preview("gpt-4o", new HttpHeaders(), "tenant-unknown");

        Assertions.assertEquals(true, preview.get("tenantScoped"));
        Assertions.assertEquals("gpt-4o", preview.get("model"));
    }

    @Test
    void shouldDelegateOutcomeRecordingToRoutingService() {
        AiGatewayProperties properties = new AiGatewayProperties();
        ProviderRoutingService routing = Mockito.mock(ProviderRoutingService.class);
        RoutingPlanResolver resolver = resolver(properties, routing);

        resolver.recordOutcome("openai", "gpt-4o-mini", 320L, true, 120L, 80L);

        Mockito.verify(routing).recordProviderOutcome("openai", "gpt-4o-mini", 320L, true, 120L, 80L);
    }

    private RoutingPlanResolver resolver(AiGatewayProperties properties, ProviderRoutingService routing) {
        return new RoutingPlanResolver(properties, routing, new TenantModelPolicyService(properties));
    }

    private AiRoutingResult route(String provider, String providerModel) {
        return AiRoutingResult.builder()
                .provider(provider)
                .providerModel(providerModel)
                .upstreamUri("https://api.example.com/v1/chat/completions")
                .routeSource("default")
                .abHit(Boolean.FALSE)
                .build();
    }

    private AiGatewayProperties tenantProperties() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getTenant().setEnabled(true);
        AiGatewayTenantProperties.TenantModelPolicy policy = new AiGatewayTenantProperties.TenantModelPolicy();
        policy.setEnabled(true);
        policy.setAllowedModels(Set.of("gpt-4o-mini"));
        policy.setModelMappings(Map.of("gpt-4o", "gpt-4o-mini"));
        properties.getTenant().getModelPolicies().put("tenant-a", policy);
        return properties;
    }
}
