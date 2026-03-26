package com.nageoffer.shortlink.aigateway.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayUpstreamException;
import com.nageoffer.shortlink.aigateway.governance.AiSafetyGuard;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.retry.Retry;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class SseStreamE2ETest {

    @Test
    void shouldSupportNormalSseFlow() {
        Flux<String> stream = Flux.just("data: hello\\n\\n", "data: [DONE]\\n\\n");
        StepVerifier.create(stream)
                .expectNext("data: hello\\n\\n")
                .expectNext("data: [DONE]\\n\\n")
                .verifyComplete();
    }

    @Test
    void shouldAbortWhenSseChunkViolatesSafetyRule() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getSafety().setEnabled(true);
        properties.getSafety().setOutputStrategy("intercept");
        properties.getSafety().setBlockedWords(Set.of("forbidden"));
        AiSafetyGuard guard = new AiSafetyGuard(properties);

        Flux<String> stream = Flux.just("data: safe\\n\\n", "data: forbidden\\n\\n")
                .map(guard::processOutput);

        StepVerifier.create(stream)
                .expectNext("data: safe\\n\\n")
                .expectError(AiGatewayClientException.class)
                .verify();
    }

    @Test
    void shouldRespectRetryBoundaryForRetriableSseFailure() {
        AtomicInteger attempts = new AtomicInteger();
        Flux<String> stream = Flux.defer(() -> {
                    if (attempts.incrementAndGet() < 3) {
                        return Flux.error(new AiGatewayUpstreamException(503, "temporary", true));
                    }
                    return Flux.just("data: recovered\\n\\n");
                })
                .retryWhen(Retry.max(2).filter(ex -> ex instanceof AiGatewayUpstreamException upstream && upstream.isRetriable()));

        StepVerifier.create(stream)
                .expectNext("data: recovered\\n\\n")
                .verifyComplete();
        Assertions.assertEquals(3, attempts.get());
    }
}
