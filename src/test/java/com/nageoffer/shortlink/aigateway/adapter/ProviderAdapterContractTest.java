package com.nageoffer.shortlink.aigateway.adapter;

import com.nageoffer.shortlink.aigateway.dto.model.AiCanonicalChatRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

class ProviderAdapterContractTest {

    @Test
    void shouldCoverSuccessFailureAndStreamScenariosForAdapters() {
        ProviderAdapter openAiAdapter = new OpenAiCompatibleProviderAdapter();
        ProviderAdapter claudeAdapter = new ClaudeCompatibleProviderAdapter();

        AiCanonicalChatRequest request = AiCanonicalChatRequest.builder()
                .clientModel("gpt-4o-mini")
                .providerModel("gpt-4o-mini")
                .messages(List.of(Map.of("role", "user", "content", "hello")))
                .stream(true)
                .build();

        // success
        String openAiBody = openAiAdapter.fromUpstreamResponse("{\"id\":\"1\"}", request).block();
        Assertions.assertTrue(openAiBody.contains("\"id\":\"1\""));

        String claudeBody = claudeAdapter.fromUpstreamResponse("{\"id\":\"msg_1\",\"content\":[{\"text\":\"ok\"}]}", request).block();
        Assertions.assertTrue(claudeBody.contains("chat.completion"));

        // failure mapping scenario（Claude 非法响应）
        Assertions.assertThrows(Exception.class, () -> claudeAdapter.fromUpstreamResponse("not-json", request).block());

        // stream scenario
        Flux<String> stream = Flux.just("data: hello\\n\\n", "data: [DONE]\\n\\n");
        StepVerifier.create(openAiAdapter.fromUpstreamSse(stream, request))
                .expectNext("data: hello\\n\\n")
                .expectNext("data: [DONE]\\n\\n")
                .verifyComplete();
    }
}
