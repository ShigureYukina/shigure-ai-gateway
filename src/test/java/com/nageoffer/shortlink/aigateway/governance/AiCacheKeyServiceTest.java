package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class AiCacheKeyServiceTest {

    @Test
    void shouldGenerateStableKeyForSameRequest() {
        AiCacheKeyService keyService = new AiCacheKeyService();
        AiChatCompletionReqDTO req = new AiChatCompletionReqDTO();
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("user");
        message.setContent("hello");
        req.setModel("gpt-4o-mini");
        req.setMessages(List.of(message));
        req.setTemperature(0.2D);
        req.setMaxTokens(128);

        String key1 = keyService.build(new TenantContext("tenant-a", "app-a", "key-a"), "openai", "gpt-4o-mini", req);
        String key2 = keyService.build(new TenantContext("tenant-a", "app-a", "key-a"), "openai", "gpt-4o-mini", req);
        Assertions.assertEquals(key1, key2);
    }

    @Test
    void shouldIsolateKeyAcrossTenants() {
        AiCacheKeyService keyService = new AiCacheKeyService();
        AiChatCompletionReqDTO req = new AiChatCompletionReqDTO();
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("user");
        message.setContent("hello");
        req.setModel("gpt-4o-mini");
        req.setMessages(List.of(message));

        String tenantAKey = keyService.build(new TenantContext("tenant-a", "app-a", "key-a"), "openai", "gpt-4o-mini", req);
        String tenantBKey = keyService.build(new TenantContext("tenant-b", "app-b", "key-b"), "openai", "gpt-4o-mini", req);

        Assertions.assertNotEquals(tenantAKey, tenantBKey);
    }

    @Test
    void shouldIsolateKeyWhenToolsDiffer() {
        AiCacheKeyService keyService = new AiCacheKeyService();
        TenantContext tenantContext = new TenantContext("tenant-a", "app-a", "key-a");

        AiChatCompletionReqDTO withoutTools = request("hello");
        AiChatCompletionReqDTO withTools = request("hello");
        withTools.getUnmapped().put("tools", List.of(java.util.Map.of("type", "function")));

        // 工具不同意味着回答可能完全不同，命中同一缓存会直接产出错答
        Assertions.assertNotEquals(
                keyService.build(tenantContext, "openai", "gpt-4o-mini", withoutTools),
                keyService.build(tenantContext, "openai", "gpt-4o-mini", withTools));
    }

    @Test
    void shouldIsolateKeyWhenMessageCarriesToolCall() {
        AiCacheKeyService keyService = new AiCacheKeyService();
        TenantContext tenantContext = new TenantContext("tenant-a", "app-a", "key-a");

        AiChatCompletionReqDTO plain = request("hello");
        AiChatCompletionReqDTO withToolCall = request("hello");
        withToolCall.getMessages().get(0).putUnmapped("tool_call_id", "call_1");

        Assertions.assertNotEquals(
                keyService.build(tenantContext, "openai", "gpt-4o-mini", plain),
                keyService.build(tenantContext, "openai", "gpt-4o-mini", withToolCall));
    }

    private AiChatCompletionReqDTO request(String content) {
        AiChatCompletionReqDTO request = new AiChatCompletionReqDTO();
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("user");
        message.setContent(content);
        request.setModel("gpt-4o-mini");
        request.setMessages(List.of(message));
        return request;
    }
}
