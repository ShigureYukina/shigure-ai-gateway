package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "short-link.ai-gateway.cache", name = "semantic-cache-enabled", havingValue = "false", matchIfMissing = true)
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
        // 兜底实现：当语义缓存未启用或未装配 Redis 实现时，不执行具体存储。
    }
}
