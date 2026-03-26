package com.nageoffer.shortlink.aigateway.governance;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

@Component
public class AiCacheKeyService {

    public String build(String provider, String providerModel, AiChatCompletionReqDTO request) {
        return build(TenantContext.global("global", "default-app", "default-key"), provider, providerModel, request);
    }

    public String build(TenantContext tenantContext, String provider, String providerModel, AiChatCompletionReqDTO request) {
        JSONObject normalized = new JSONObject();
        normalized.put("tenantId", tenantContext.tenantId());
        normalized.put("appId", tenantContext.appId());
        normalized.put("keyId", tenantContext.keyId());
        normalized.put("provider", provider);
        normalized.put("model", providerModel);
        normalized.put("messages", request.getMessages());
        normalized.put("temperature", request.getTemperature());
        normalized.put("max_tokens", request.getMaxTokens());
        String raw = JSON.toJSONString(normalized);
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }
}
