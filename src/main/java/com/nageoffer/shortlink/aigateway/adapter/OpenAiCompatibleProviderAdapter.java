package com.nageoffer.shortlink.aigateway.adapter;

import com.nageoffer.shortlink.aigateway.dto.model.AiCanonicalChatRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI 兼容上游适配器。
 * <p>
 * 上游本身就是 OpenAI 协议，因此以"透传为主"：显式字段 + 扩展字段合并后直发，
 * 响应与 SSE 不做结构改写，只保证流式请求带上用量回传开关。
 */
@Component
public class OpenAiCompatibleProviderAdapter implements ProviderAdapter {

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public Object toUpstreamRequest(AiCanonicalChatRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.getProviderModel());
        payload.put("messages", request.getMessages());
        payload.put("stream", request.getStream());
        if (request.getTemperature() != null) {
            payload.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            payload.put("max_tokens", request.getMaxTokens());
        }
        if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
            payload.put("metadata", request.getMetadata());
        }
        // 扩展字段透传：tools / response_format / top_p 等原样带上；已显式建模的字段优先。
        if (request.getExtra() != null && !request.getExtra().isEmpty()) {
            request.getExtra().forEach(payload::putIfAbsent);
        }
        if (Boolean.TRUE.equals(request.getStream())) {
            payload.put("stream_options", streamOptions(payload.get("stream_options")));
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

    /**
     * 强制打开 {@code include_usage}。
     * <p>
     * 流式请求若不回传 usage，网关无法按实际用量结算 token 配额与成本，
     * 只能把预估值当成终值。除该开关外不改写客户端其它 stream_options 选项。
     */
    private Map<String, Object> streamOptions(Object existing) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (existing instanceof Map<?, ?> existingMap) {
            existingMap.forEach((key, value) -> options.put(String.valueOf(key), value));
        }
        options.put("include_usage", true);
        return options;
    }
}
