package com.nageoffer.shortlink.aigateway.perf;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.governance.AiCacheKeyService;
import com.nageoffer.shortlink.aigateway.governance.QuotaKeyGenerator;
import com.nageoffer.shortlink.aigateway.governance.TokenEstimator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import java.net.InetSocketAddress;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

class PerformanceSmokeBaselineTest {

    @Test
    void shouldKeepCoreGovernanceFunctionsWithinSmokeThreshold() {
        AiGatewayProperties properties = new AiGatewayProperties();
        TokenEstimator tokenEstimator = new TokenEstimator(properties);
        QuotaKeyGenerator quotaKeyGenerator = new QuotaKeyGenerator(properties);
        AiCacheKeyService cacheKeyService = new AiCacheKeyService();

        AiChatCompletionReqDTO request = new AiChatCompletionReqDTO();
        request.setModel("gpt-4o-mini");
        request.setMaxTokens(256);
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("user");
        message.setContent("performance smoke test payload for ai gateway");
        request.setMessages(List.of(message));

        ServerHttpRequest httpRequest = MockServerHttpRequest.post("/v1/chat/completions")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 50000))
                .build();

        Instant start = Instant.now();
        for (int i = 0; i < 20_000; i++) {
            tokenEstimator.estimate(request);
            quotaKeyGenerator.build(null, httpRequest, "openai", "gpt-4o-mini");
            cacheKeyService.build("openai", "gpt-4o-mini", request);
        }
        long elapsedMillis = Duration.between(start, Instant.now()).toMillis();

        Assertions.assertTrue(elapsedMillis < 2500, "性能冒烟阈值超限，elapsed=" + elapsedMillis + "ms");
    }
}
