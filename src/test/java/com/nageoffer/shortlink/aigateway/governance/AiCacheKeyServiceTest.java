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
}
