package com.nageoffer.shortlink.aigateway.observability;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class AiRequestTraceBusTest {

    @Test
    void shouldDeliverToSubscribersAndKeepRecentSnapshot() {
        AiGatewayProperties properties = new AiGatewayProperties();
        AiRequestTraceBus bus = new AiRequestTraceBus(properties);
        List<AiRequestTraceEvent> received = Collections.synchronizedList(new ArrayList<>());

        Disposable subscription = bus.stream().subscribe(received::add);
        bus.publish(event("req-1", AiRequestTraceEvent.STAGE_ROUTING));
        bus.publish(event("req-1", AiRequestTraceEvent.STAGE_COMPLETED));
        subscription.dispose();

        Assertions.assertEquals(2, received.size());
        Assertions.assertEquals(2, bus.recent(10).size());

        // 没人订阅时也要能回看最近事件，页面首屏靠它
        Assertions.assertEquals(0, bus.subscriberCount());
    }

    @Test
    void shouldCapRecentSnapshotAndDropOldest() {
        AiGatewayProperties properties = new AiGatewayProperties();
        AiRequestTraceBus bus = new AiRequestTraceBus(properties);

        for (int i = 0; i < 5; i++) {
            bus.publish(event("req-" + i, AiRequestTraceEvent.STAGE_UPSTREAM));
        }

        Assertions.assertEquals(2, bus.recent(2).size());
        Assertions.assertEquals("req-3", bus.recent(2).get(0).getRequestId());
        Assertions.assertEquals("req-4", bus.recent(2).get(1).getRequestId());
    }

    @Test
    void shouldStampTimestampWhenPublisherOmitsIt() {
        AiGatewayProperties properties = new AiGatewayProperties();
        AiRequestTraceBus bus = new AiRequestTraceBus(properties);

        bus.publish(event("req-1", AiRequestTraceEvent.STAGE_ROUTING));

        Assertions.assertNotNull(bus.recent(1).get(0).getTimestamp());
    }

    @Test
    void shouldShortCircuitWhenDisabled() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getObservability().setTraceStreamEnabled(false);
        AiRequestTraceBus bus = new AiRequestTraceBus(properties);
        List<AiRequestTraceEvent> received = Collections.synchronizedList(new ArrayList<>());
        Disposable subscription = bus.stream().subscribe(received::add);

        bus.publish(event("req-1", AiRequestTraceEvent.STAGE_ROUTING));
        subscription.dispose();

        Assertions.assertTrue(received.isEmpty());
        Assertions.assertTrue(bus.recent(10).isEmpty());
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
