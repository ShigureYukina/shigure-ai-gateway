package com.nageoffer.shortlink.aigateway.governance;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UsageExtractorTest {

    @Test
    void shouldExtractUsageFieldsFromValidBody() {
        UsageExtractor extractor = new UsageExtractor();
        String body = """
                {
                  "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 8,
                    "total_tokens": 20
                  }
                }
                """;

        UsageDetail usage = extractor.extractUsage(body);

        Assertions.assertNotNull(usage);
        Assertions.assertEquals(12L, usage.getPromptTokens());
        Assertions.assertEquals(8L, usage.getCompletionTokens());
        Assertions.assertEquals(20L, usage.getTotalTokens());
        Assertions.assertEquals(20L, extractor.extractTotalTokens(body));
    }

    @Test
    void shouldReturnNullWhenUsageMissingOrInvalid() {
        UsageExtractor extractor = new UsageExtractor();

        Assertions.assertNull(extractor.extractUsage("{}"));
        Assertions.assertNull(extractor.extractUsage("{\"usage\": null}"));
        Assertions.assertNull(extractor.extractUsage("not-json"));
        Assertions.assertNull(extractor.extractTotalTokens("{}"));
    }
}
