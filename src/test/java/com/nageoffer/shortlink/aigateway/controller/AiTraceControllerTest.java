package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.observability.AiRequestTraceBus;
import com.nageoffer.shortlink.aigateway.observability.AiRequestTraceEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class AiTraceControllerTest {

    @Test
    void shouldExposeRecentEventsOverHttp() {
        AiGatewayProperties properties = new AiGatewayProperties();
        AiRequestTraceBus bus = new AiRequestTraceBus(properties);
        bus.publish(event("req-1", AiRequestTraceEvent.STAGE_ROUTING));
        bus.publish(event("req-1", AiRequestTraceEvent.STAGE_COMPLETED));

        WebTestClient client = WebTestClient.bindToController(new AiTraceController(bus)).build();

        client.get()
                .uri("/v1/trace/recent?limit=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(2)
                .jsonPath("$.events[0].stage").isEqualTo("routing")
                .jsonPath("$.events[1].stage").isEqualTo("completed");
    }

    @Test
    void shouldPushLiveEventToStreamSubscriber() {
        AiGatewayProperties properties = new AiGatewayProperties();
        AiRequestTraceBus bus = new AiRequestTraceBus(properties);
        List<ServerSentEvent<AiRequestTraceEvent>> received = Collections.synchronizedList(new ArrayList<>());

        Disposable subscription = new AiTraceController(bus).stream().subscribe(received::add);
        bus.publish(event("req-9", AiRequestTraceEvent.STAGE_FIRST_TOKEN));
        subscription.dispose();

        Assertions.assertEquals(1, received.size());
        Assertions.assertEquals("trace", received.get(0).event());
        Assertions.assertEquals("req-9", received.get(0).data().getRequestId());
    }

    private AiRequestTraceEvent event(String requestId, String stage) {
        return AiRequestTraceEvent.builder()
                .requestId(requestId)
                .stage(stage)
                .status("ok")
                .provider("openai")
                .model("gpt-4o-mini")
                .build();
    }
}
