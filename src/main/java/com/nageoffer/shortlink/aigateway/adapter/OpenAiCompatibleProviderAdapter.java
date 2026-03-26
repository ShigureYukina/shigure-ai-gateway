package com.nageoffer.shortlink.aigateway.adapter;

import com.nageoffer.shortlink.aigateway.dto.model.AiCanonicalChatRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@Component
public class OpenAiCompatibleProviderAdapter implements ProviderAdapter {

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public Object toUpstreamRequest(AiCanonicalChatRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", request.getProviderModel());
        payload.put("messages", request.getMessages());
        payload.put("stream", request.getStream());
        if (request.getTemperature() != null) {
            payload.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            payload.put("max_tokens", request.getMaxTokens());
        }
        return payload;
    }

    @Override
    public Mono<String> fromUpstreamResponse(String upstreamBody, AiCanonicalChatRequest request) {
        return Mono.just(upstreamBody);
    }

    @Override
    public Flux<String> fromUpstreamSse(Flux<String> upstreamFlux, AiCanonicalChatRequest request) {
        return upstreamFlux;
    }
}
