package com.nageoffer.shortlink.aigateway.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上游元数据解析。
 * <p>
 * 把外部数据源的形状挡在这里：解析失败只返回空结果并记日志，不要让一次上游改版
 * 直接变成定时任务里的异常刷屏。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpstreamMetadataParser {

    private final ObjectMapper objectMapper;

    /**
     * 解析 models.dev 的价格快照。
     * <p>
     * 结构为 {@code { providerId: { models: { modelId: { id, cost: { input, output } } } } }}，
     * 其中 cost 的单位是"每百万 token 美元"，这里统一换算成配置里使用的"每 1k token 美元"。
     * <p>
     * 同一个模型可能被多个聚合渠道收录（{@code openai/gpt-4o}），因此除完整 id 外
     * 额外按去掉前缀的短名建索引，这样配置里写 {@code gpt-4o} 也能命中。
     */
    public Map<String, AiGatewayProperties.ModelPrice> parsePrices(String json) {
        Map<String, AiGatewayProperties.ModelPrice> prices = new LinkedHashMap<>();
        JsonNode root = readTree(json);
        if (root == null) {
            return prices;
        }
        root.fields().forEachRemaining(provider -> {
            JsonNode models = provider.getValue().path("models");
            models.fields().forEachRemaining(model -> {
                JsonNode cost = model.getValue().path("cost");
                if (!cost.hasNonNull("input") && !cost.hasNonNull("output")) {
                    return;
                }
                AiGatewayProperties.ModelPrice price = new AiGatewayProperties.ModelPrice();
                price.setInputPer1k(cost.path("input").asDouble(0D) / 1000D);
                price.setOutputPer1k(cost.path("output").asDouble(0D) / 1000D);

                String modelId = model.getValue().path("id").asText(model.getKey());
                if (!StringUtils.hasText(modelId)) {
                    return;
                }
                prices.put(modelId, price);
                int slash = modelId.indexOf('/');
                if (slash > 0 && slash < modelId.length() - 1) {
                    prices.putIfAbsent(modelId.substring(slash + 1), price);
                }
            });
        });
        return prices;
    }

    /**
     * 解析模型清单：OpenAI 与 Anthropic 的 {@code /v1/models} 都是 {@code { data: [ { id } ] }} 形状。
     */
    public List<String> parseModelIds(String json) {
        Set<String> models = new LinkedHashSet<>();
        JsonNode root = readTree(json);
        if (root == null) {
            return List.of();
        }
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode item : data) {
                String id = item.path("id").asText(null);
                if (StringUtils.hasText(id)) {
                    models.add(id);
                }
            }
        }
        return new ArrayList<>(models);
    }

    private JsonNode readTree(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            log.warn("failed to parse upstream metadata: {}", ex.getMessage());
            return null;
        }
    }
}
