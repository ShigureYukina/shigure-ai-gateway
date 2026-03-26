package com.nageoffer.shortlink.aigateway.adapter;

import com.nageoffer.shortlink.aigateway.dto.model.AiCanonicalChatRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProviderAdapter {

    String providerName();

    Object toUpstreamRequest(AiCanonicalChatRequest request);

    Mono<String> fromUpstreamResponse(String upstreamBody, AiCanonicalChatRequest request);

    Flux<String> fromUpstreamSse(Flux<String> upstreamFlux, AiCanonicalChatRequest request);
}
