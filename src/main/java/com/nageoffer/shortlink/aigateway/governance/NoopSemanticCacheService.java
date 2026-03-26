package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class NoopSemanticCacheService implements SemanticCacheService {

    private final AiGatewayProperties properties;

    @Override
    public Optional<String> find(String provider, String model, AiChatCompletionReqDTO request) {
        if (!properties.getCache().isSemanticCacheEnabled()) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    @Override
    public void put(String provider, String model, AiChatCompletionReqDTO request, String response) {
        if (!properties.getCache().isSemanticCacheEnabled()) {
            return;
        }
        // 预留语义缓存扩展点：当前默认关闭，不执行具体存储
    }
}
