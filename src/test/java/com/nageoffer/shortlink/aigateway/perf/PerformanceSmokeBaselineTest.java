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

        HttpHeaders headers = new HttpHeaders();
        headers.set("userId", "u-100");
        headers.set("X-Forwarded-For", "127.0.0.1");
        headers.set("X-Consumer", "perf");

        Instant start = Instant.now();
        for (int i = 0; i < 20_000; i++) {
            tokenEstimator.estimate(request);
            quotaKeyGenerator.build(headers, "openai", "gpt-4o-mini");
            cacheKeyService.build("openai", "gpt-4o-mini", request);
        }
        long elapsedMillis = Duration.between(start, Instant.now()).toMillis();

        Assertions.assertTrue(elapsedMillis < 2500, "性能冒烟阈值超限，elapsed=" + elapsedMillis + "ms");
    }
}
