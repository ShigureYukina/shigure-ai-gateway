package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class TokenEstimatorTest {

    @Test
    void shouldReserveAtLeastMinTokens() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getRateLimit().setMinTokenReserve(256L);
        TokenEstimator tokenEstimator = new TokenEstimator(properties);

        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("user");
        message.setContent("hello");
        AiChatCompletionReqDTO request = new AiChatCompletionReqDTO();
        request.setModel("gpt-4o-mini");
        request.setMessages(List.of(message));
        request.setMaxTokens(10);

        TokenEstimateResult result = tokenEstimator.estimate(request);
        Assertions.assertTrue(result.getOutputReserve() >= 256);
    }
}
