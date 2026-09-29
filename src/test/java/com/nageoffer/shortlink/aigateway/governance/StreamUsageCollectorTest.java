package com.nageoffer.shortlink.aigateway.governance;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StreamUsageCollectorTest {

    private final StreamUsageCollector collector = new StreamUsageCollector(new UsageExtractor());

    @Test
    void shouldAccumulateUsageFromFinalChunk() {
        collector.accept("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}");
        Assertions.assertNull(collector.usage());

        collector.accept("{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}");

        Assertions.assertNotNull(collector.usage());
        Assertions.assertEquals(15L, collector.usage().getTotalTokens());
    }

    @Test
    void shouldMarkDoneWhenTerminatorSeen() {
        Assertions.assertFalse(collector.doneSeen());

        collector.accept("[DONE]");

        Assertions.assertTrue(collector.doneSeen());
        Assertions.assertNull(collector.usage());
    }

    @Test
    void shouldIgnoreMalformedAndBlankChunks() {
        collector.accept(null);
        collector.accept("   ");
        collector.accept("data: not-json");
        collector.accept("{\"usage\":{\"prompt_tokens\":null,\"completion_tokens\":null}}");

        Assertions.assertNull(collector.usage());
        Assertions.assertFalse(collector.doneSeen());
    }
}
