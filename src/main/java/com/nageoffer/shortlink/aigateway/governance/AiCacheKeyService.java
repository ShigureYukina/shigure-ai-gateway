package com.nageoffer.shortlink.aigateway.governance;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 缓存键构造。
 * <p>
 * 键必须覆盖所有会改变模型输出的输入，否则会出现"内容不同却命中同一缓存"的错答。
 * 特别注意 {@code tools} / {@code response_format} 这类扩展字段：
 * 它们此前不在请求模型里，携带不同工具的请求会互相命中，属于典型的静默错误。
 */
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
        normalized.put("messages", normalizeMessages(request.getMessages()));
        normalized.put("temperature", request.getTemperature());
        normalized.put("max_tokens", request.getMaxTokens());
        normalized.put("extra", request.getUnmapped());
        String raw = JSON.toJSONString(normalized);
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 显式归一化消息结构，不依赖序列化框架对匿名字段的处理。
     */
    private JSONArray normalizeMessages(List<AiChatCompletionMessage> messages) {
        JSONArray normalized = new JSONArray();
        if (messages == null) {
            return normalized;
        }
        for (AiChatCompletionMessage message : messages) {
            if (message == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("role", message.getRole());
            item.put("content", message.getContent());
            if (message.getUnmapped() != null && !message.getUnmapped().isEmpty()) {
                item.put("extra", message.getUnmapped());
            }
            normalized.add(item);
        }
        return normalized;
    }
}
