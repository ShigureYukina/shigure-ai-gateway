package com.nageoffer.shortlink.aigateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 多租户域属性组：对应 {@code short-link.ai-gateway.tenant.*}。
 * <p>
 * 绑定入口仍是 {@link AiGatewayProperties}——本类不注册为独立配置 Bean，
 * 只做属性分组，因此 yml 键与注入点都不变。
 */
@Data
public class AiGatewayTenantProperties {

    @Valid
    private Persistence persistence = new Persistence();

    /**
     * 是否启用平台 API Key 鉴权。
     */
    private boolean enabled = false;

    /**
     * 未启用多租户时使用的默认上下文标识。
     */
    private String defaultTenantId = "global";

    private String defaultAppId = "default-app";

    private String defaultKeyId = "default-key";

    /**
     * 配置化 API Key 凭证，key 为内部凭证 ID。
     */
    private Map<String, TenantApiKeyCredential> apiKeys = new HashMap<>();

    /**
     * 租户模型策略，key 为 tenantId。
     */
    private Map<String, TenantModelPolicy> modelPolicies = new HashMap<>();

    /**
     * 租户配额策略，key 为 tenantId。
     */
    private Map<String, TenantQuotaPolicy> quotaPolicies = new HashMap<>();

    /**
     * 租户自带上游凭证（BYOK）：tenantId -> provider -> 凭证。
     * <p>
     * 凭证结构归属上游域（{@link AiGatewayProperties.ProviderCredential}），此处只引用不复制，
     * 避免平台级与租户级凭证的字段各自漂移。
     */
    private Map<String, Map<String, AiGatewayProperties.ProviderCredential>> providerCredentials = new HashMap<>();

    @Data
    public static class Persistence {

        /**
         * 是否启用多租户配置的数据库读取。
         */
        private boolean enabled = false;
    }

    @Data
    public static class TenantApiKeyCredential {

        @NotBlank
        private String apiKey;

        @NotBlank
        private String tenantId;

        @NotBlank
        private String appId;

        private String keyId;

        private boolean enabled = true;

        private Instant expiresAt;
    }

    @Data
    public static class TenantModelPolicy {

        private boolean enabled = true;

        /**
         * 允许访问的模型集合。
         */
        private Set<String> allowedModels = new HashSet<>();

        /**
         * 请求模型到实际模型的映射。
         */
        private Map<String, String> modelMappings = new HashMap<>();

        /**
         * 默认模型别名，用于租户级模型覆写。
         */
        private String defaultModelAlias = "default";

        /**
         * 租户默认模型。
         */
        private String defaultModel;
    }

    @Data
    public static class TenantQuotaPolicy {

        private boolean enabled = true;

        private Long tokenQuotaPerMinute;

        private Long tokenQuotaPerDay;

        private Long tokenQuotaPerMonth;
    }
}
