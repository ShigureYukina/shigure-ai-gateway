package com.nageoffer.shortlink.aigateway.config;

import lombok.Data;

import java.time.Duration;

/**
 * 上游元数据同步域属性组：对应 {@code short-link.ai-gateway.sync.*}。
 * <p>
 * 绑定入口仍是 {@link AiGatewayProperties}——本类不注册为独立配置 Bean，
 * 只做属性分组，因此 yml 键与注入点都不变。
 */
@Data
public class AiGatewaySyncProperties {

    private PriceSync price = new PriceSync();

    private ModelSync model = new ModelSync();

    /**
     * 拉取上游元数据的单次请求超时。
     */
    private Duration timeout = Duration.ofSeconds(10);

    /**
     * 价格自动同步：从 models.dev 拉取模型单价。
     * <p>
     * 注意 initial-delay / interval 不在这里绑定：它们由 {@code @Scheduled} 直接读同一份配置，
     * 且必须写成 ISO-8601（如 {@code PT60S}），写成 {@code 60s} 会导致启动失败。
     */
    @Data
    public static class PriceSync {

        private boolean enabled = true;

        private String url = "https://models.dev/api.json";
    }

    /**
     * 模型清单自动同步：从各渠道的模型端点发现可用模型。
     */
    @Data
    public static class ModelSync {

        private boolean enabled = true;

        /**
         * 模型清单路径，OpenAI 与 Anthropic 都是 /v1/models。
         */
        private String path = "/v1/models";
    }
}
