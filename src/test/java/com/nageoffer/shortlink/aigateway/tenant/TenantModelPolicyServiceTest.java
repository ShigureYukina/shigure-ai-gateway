package com.nageoffer.shortlink.aigateway.tenant;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

class TenantModelPolicyServiceTest {

    @Test
    void shouldAllowMappedAndWhitelistedModel() {
        TenantModelPolicyService service = new TenantModelPolicyService(baseProperties());

        String model = service.resolveModel(new TenantContext("tenant-a", "app-a", "key-a"), "default");

        Assertions.assertEquals("gpt-4o-mini-compatible", model);
    }

    @Test
    void shouldRejectDisallowedModel() {
        TenantModelPolicyService service = new TenantModelPolicyService(baseProperties());

        AiGatewayClientException exception = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.resolveModel(new TenantContext("tenant-a", "app-a", "key-a"), "claude-3-5-sonnet-latest"));

        Assertions.assertEquals(AiGatewayErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    @Test
    void shouldRejectInvalidDefaultModel() {
        AiGatewayProperties properties = baseProperties();
        properties.getTenant().getModelPolicies().get("tenant-a").setModelMappings(new HashMap<>());
        properties.getTenant().getModelPolicies().get("tenant-a").setDefaultModel("claude-3-5-sonnet-latest");
        TenantModelPolicyService service = new TenantModelPolicyService(properties);

        AiGatewayClientException exception = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.resolveModel(new TenantContext("tenant-a", "app-a", "key-a"), "default"));

        Assertions.assertEquals(AiGatewayErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    @Test
    void shouldPreferDatabaseBackedPolicyWhenAvailable() {
        AiGatewayProperties properties = baseProperties();
        TenantConfigQueryService queryService = Mockito.mock(TenantConfigQueryService.class);
        AiGatewayProperties.TenantModelPolicy dbPolicy = new AiGatewayProperties.TenantModelPolicy();
        dbPolicy.setEnabled(true);
        dbPolicy.setAllowedModels(Set.of("deepseek-chat"));
        dbPolicy.setModelMappings(Map.of("default", "deepseek-chat"));
        dbPolicy.setDefaultModelAlias("default");
        dbPolicy.setDefaultModel("deepseek-chat");
        Mockito.when(queryService.findModelPolicy("tenant-a")).thenReturn(java.util.Optional.of(dbPolicy));
        TenantModelPolicyService service = new TenantModelPolicyService(properties, queryService);

        String model = service.resolveModel(new TenantContext("tenant-a", "app-a", "key-a"), "default");

        Assertions.assertEquals("deepseek-chat", model);
    }

    private AiGatewayProperties baseProperties() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getTenant().setEnabled(true);

        AiGatewayProperties.TenantModelPolicy policy = new AiGatewayProperties.TenantModelPolicy();
        policy.setAllowedModels(Set.of("gpt-4o-mini-compatible", "gpt-4o-mini"));
        policy.setModelMappings(Map.of("default", "gpt-4o-mini-compatible"));
        policy.setDefaultModelAlias("default");
        policy.setDefaultModel("gpt-4o-mini-compatible");

        properties.getTenant().setModelPolicies(Map.of("tenant-a", policy));
        return properties;
    }
}
