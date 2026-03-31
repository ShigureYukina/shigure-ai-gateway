package com.nageoffer.shortlink.aigateway.service;

import com.nageoffer.shortlink.aigateway.adapter.ProviderAdapter;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTracer;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.governance.AiCacheControlService;
import com.nageoffer.shortlink.aigateway.governance.AiCacheKeyService;
import com.nageoffer.shortlink.aigateway.governance.AiCacheStatsService;
import com.nageoffer.shortlink.aigateway.governance.AiSafetyGuard;
import com.nageoffer.shortlink.aigateway.governance.NoopSemanticCacheService;
import com.nageoffer.shortlink.aigateway.governance.RedisResponseCacheService;
import com.nageoffer.shortlink.aigateway.governance.RedisTokenQuotaService;
import com.nageoffer.shortlink.aigateway.governance.UsageExtractor;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.plugin.PluginChainService;
import com.nageoffer.shortlink.aigateway.routing.ProviderRoutingService;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import com.nageoffer.shortlink.aigateway.tenant.TenantModelPolicyService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

class AiGatewayServiceTenantModelPolicyTest {

    @Test
    void shouldRejectDisallowedModelBeforeRouting() {
        ProviderRoutingService providerRoutingService = Mockito.mock(ProviderRoutingService.class);
        TenantModelPolicyService tenantModelPolicyService = Mockito.mock(TenantModelPolicyService.class);
        Mockito.when(tenantModelPolicyService.resolveModel(Mockito.any(), Mockito.anyString()))
                .thenThrow(new AiGatewayClientException(AiGatewayErrorCode.FORBIDDEN, "当前租户无权访问模型: claude-3-5-sonnet-latest"));

        AiGatewayService service = new AiGatewayService(
                WebClient.builder().build(),
                providerRoutingService,
                tenantModelPolicyService,
                List.<ProviderAdapter>of(),
                Mockito.mock(AiGatewayMetricsRecorder.class),
                Mockito.mock(AiSafetyGuard.class),
                Mockito.mock(RedisTokenQuotaService.class),
                Mockito.mock(UsageExtractor.class),
                Mockito.mock(AiCacheControlService.class),
                Mockito.mock(AiCacheKeyService.class),
                Mockito.mock(AiCacheStatsService.class),
                Mockito.mock(RedisResponseCacheService.class),
                Mockito.mock(NoopSemanticCacheService.class),
                Mockito.mock(PluginChainService.class),
                new AiGatewayProperties(),
                Mockito.mock(ReactiveCircuitBreakerFactory.class),
                Mockito.mock(AiGatewayTracer.class)
        );

        AiGatewayClientException exception = Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.chatCompletion(
                        request("claude-3-5-sonnet-latest"),
                        new HttpHeaders(),
                        new TenantContext("tenant-a", "app-a", "key-a")
                ).block());

        Assertions.assertEquals(AiGatewayErrorCode.FORBIDDEN, exception.getErrorCode());
        Mockito.verifyNoInteractions(providerRoutingService);
    }

    private AiChatCompletionReqDTO request(String model) {
        AiChatCompletionReqDTO request = new AiChatCompletionReqDTO();
        request.setModel(model);
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("user");
        message.setContent("hello");
        request.setMessages(List.of(message));
        request.setStream(false);
        return request;
    }
}
