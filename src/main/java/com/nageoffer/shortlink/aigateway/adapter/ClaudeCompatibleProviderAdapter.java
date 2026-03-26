package com.nageoffer.shortlink.aigateway.adapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nageoffer.shortlink.aigateway.dto.model.AiCanonicalChatRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ClaudeCompatibleProviderAdapter implements ProviderAdapter {

    @Override
    public String providerName() {
        return "claude";
    }

    @Override
    public Object toUpstreamRequest(AiCanonicalChatRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", request.getProviderModel());
        payload.put("stream", request.getStream());
        if (request.getMaxTokens() != null) {
            payload.put("max_tokens", request.getMaxTokens());
        }
        if (request.getTemperature() != null) {
            payload.put("temperature", request.getTemperature());
        }
        payload.put("messages", convertMessages(request.getMessages()));
        return payload;
    }

    @Override
    public Mono<String> fromUpstreamResponse(String upstreamBody, AiCanonicalChatRequest request) {
        JSONObject upstreamJson = JSON.parseObject(upstreamBody);
        JSONObject normalized = new JSONObject();
        normalized.put("id", upstreamJson.getString("id"));
        normalized.put("object", "chat.completion");
        normalized.put("model", request.getClientModel());
        JSONArray choices = new JSONArray();
        JSONObject choice = new JSONObject();
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", extractText(upstreamJson));
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        choices.add(choice);
        normalized.put("choices", choices);
        return Mono.just(normalized.toJSONString());
    }

    @Override
    public Flux<String> fromUpstreamSse(Flux<String> upstreamFlux, AiCanonicalChatRequest request) {
        return upstreamFlux.map(each -> each);
    }

    private List<Map<String, String>> convertMessages(List<Map<String, String>> messages) {
        return messages;
    }

    private String extractText(JSONObject upstreamJson) {
        JSONArray content = upstreamJson.getJSONArray("content");
        if (content == null || content.isEmpty()) {
            return "";
        }
        JSONObject first = content.getJSONObject(0);
        return first.getString("text");
    }
}
