package com.nageoffer.shortlink.aigateway.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class UpstreamMetadataParserTest {

    private final UpstreamMetadataParser parser = new UpstreamMetadataParser(new ObjectMapper());

    @Test
    void shouldParsePricesAndConvertPerMillionToPerThousand() {
        String json = """
                {
                  "openai": {
                    "id": "openai",
                    "models": {
                      "gpt-4o-mini": {
                        "id": "gpt-4o-mini",
                        "cost": { "input": 150, "output": 600, "cache_read": 75 }
                      }
                    }
                  }
                }
                """;

        Map<String, AiGatewayProperties.ModelPrice> prices = parser.parsePrices(json);

        // models.dev 的 cost 是"每百万 token 美元"，配置里用的是"每 1k"
        Assertions.assertEquals(0.15D, prices.get("gpt-4o-mini").getInputPer1k(), 0.000001);
        Assertions.assertEquals(0.6D, prices.get("gpt-4o-mini").getOutputPer1k(), 0.000001);
    }

    @Test
    void shouldIndexAggregatorPrefixedModelByBareNameToo() {
        String json = """
                {
                  "openrouter": {
                    "models": {
                      "openai/gpt-4o": { "id": "openai/gpt-4o", "cost": { "input": 2500, "output": 10000 } }
                    }
                  }
                }
                """;

        Map<String, AiGatewayProperties.ModelPrice> prices = parser.parsePrices(json);

        Assertions.assertTrue(prices.containsKey("openai/gpt-4o"));
        Assertions.assertTrue(prices.containsKey("gpt-4o"));
    }

    @Test
    void shouldSkipModelsWithoutCostAndSurviveBrokenJson() {
        String json = """
                {
                  "openai": {
                    "models": {
                      "no-cost-model": { "id": "no-cost-model" },
                      "free-model": { "id": "free-model", "cost": {} }
                    }
                  }
                }
                """;

        Assertions.assertTrue(parser.parsePrices(json).isEmpty());
        Assertions.assertTrue(parser.parsePrices("not-json").isEmpty());
        Assertions.assertTrue(parser.parsePrices("").isEmpty());
    }

    @Test
    void shouldParseModelIdsFromOpenAiAndAnthropicShapes() {
        List<String> models = parser.parseModelIds("""
                { "object": "list", "data": [ { "id": "gpt-4o" }, { "id": "gpt-4o-mini" }, { "id": "gpt-4o" } ] }
                """);

        Assertions.assertEquals(List.of("gpt-4o", "gpt-4o-mini"), models);
        Assertions.assertTrue(parser.parseModelIds("{ \"data\": [] }").isEmpty());
        Assertions.assertTrue(parser.parseModelIds("boom").isEmpty());
    }
}
