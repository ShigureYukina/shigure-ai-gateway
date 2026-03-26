package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;

import java.util.Optional;

public interface SemanticCacheService {

    Optional<String> find(String provider, String model, AiChatCompletionReqDTO request);

    void put(String provider, String model, AiChatCompletionReqDTO request, String response);
}
