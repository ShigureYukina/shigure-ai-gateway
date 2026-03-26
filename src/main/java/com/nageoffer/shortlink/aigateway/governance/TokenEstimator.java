package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TokenEstimator {

    private final AiGatewayProperties properties;

    public TokenEstimateResult estimate(AiChatCompletionReqDTO request) {
        int chars = request.getMessages().stream()
                .mapToInt(each -> each.getContent() == null ? 0 : each.getContent().length())
                .sum();
        long inputEstimated = Math.max(1, chars / 4L);
        long outputReserve = request.getMaxTokens() != null
                ? request.getMaxTokens()
                : properties.getRateLimit().getMinTokenReserve();
        outputReserve = Math.max(outputReserve, properties.getRateLimit().getMinTokenReserve());
        return TokenEstimateResult.builder()
                .inputEstimated(inputEstimated)
                .outputReserve(outputReserve)
                .build();
    }
}
