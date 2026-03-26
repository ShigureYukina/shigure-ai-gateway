package com.nageoffer.shortlink.aigateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@ConfigurationProperties(prefix = "short-link.ai-gateway")
public class AiGatewayProperties {

    @Valid
    private Upstream upstream = new Upstream();

    @Valid
    private TimeoutRetry timeoutRetry = new TimeoutRetry();

    @Valid
    private Plugin plugin = new Plugin();

    @Valid
    private RateLimit rateLimit = new RateLimit();

    @Valid
    private Cache cache = new Cache();

    @Valid
    private Safety safety = new Safety();

    @Valid
    private Observability observability = new Observability();

    @Valid
    private Routing routing = new Routing();

    @Valid
    private Security security = new Security();

    @Valid
    private Tenant tenant = new Tenant();

    @Data
    public static class Upstream {

        @NotBlank
        private String defaultProvider = "openai";

        private Map<String, String> providerBaseUrl = new HashMap<>();

        private Map<String, String> modelAlias = new HashMap<>();
    }

    @Data
    public static class TimeoutRetry {

        private Duration connectTimeout = Duration.ofSeconds(5);

        private Duration readTimeout = Duration.ofSeconds(60);

        private Integer maxRetries = 1;

        private Set<Integer> retryStatusCodes = new HashSet<>(Set.of(429, 500, 502, 503, 504));

        private Map<String, RoutePolicy> routePolicy = new HashMap<>();
    }

    @Data
    public static class RoutePolicy {

        private Duration requestTimeout;

        private Integer maxRetries;

        private Set<Integer> retryStatusCodes = new HashSet<>();
    }

    @Data
    public static class Plugin {

        private boolean enabled = true;

        private Map<String, Boolean> pluginEnabledMap = new HashMap<>();

        /**
         * 全局插件启用列表，按声明顺序
         */
        private List<String> globalPlugins = new ArrayList<>();

        /**
         * 路由级插件：key 可为 provider 或 provider:model
         */
        private Map<String, List<String>> routePlugins = new HashMap<>();
    }

    @Data
    public static class RateLimit {

        private boolean enabled = false;

        private Long defaultTokenQuotaPerMinute = 60000L;

        private Long defaultTokenQuotaPerDay = 1000000L;

        private Long minTokenReserve = 128L;

        private List<String> keyDimensions = new ArrayList<>(List.of("userId", "ip", "consumer"));
    }

    @Data
    public static class Cache {

        private boolean enabled = false;

        private Duration ttl = Duration.ofMinutes(2);

        private boolean semanticCacheEnabled = false;

        /**
         * 每次缓存命中的节省成本估算（USD）
         */
        private Double estimatedCostPerHitUsd = 0.002D;
    }

    @Data
    public static class Safety {

        private boolean enabled = false;

        /**
         * 输入阶段策略：intercept / audit
         */
        private String inputStrategy = "intercept";

        private String outputStrategy = "intercept";

        private Set<String> blockedWords = new HashSet<>();

        /**
         * Prompt Injection 检测规则（子串匹配，忽略大小写）
         */
        private List<String> promptInjectionPatterns = new ArrayList<>(List.of(
                "ignore previous instructions",
                "system prompt",
                "reveal hidden instructions",
                "绕过",
                "忽略以上"
        ));

        /**
         * PII 检测正则（命中后按策略拦截/脱敏）
         */
        private List<String> piiPatterns = new ArrayList<>(List.of(
                "\\b1\\d{10}\\b",
                "\\b[0-9]{17}[0-9Xx]\\b",
                "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
        ));

        private String redactMask = "***";
    }

    @Data
    public static class Observability {

        /**
         * 是否启用 tenant 维度 Prometheus 指标。
         */
        private boolean tenantMetricsEnabled = true;

        /**
         * 是否启用 tenant cache 事件指标。
         */
        private boolean cacheEventMetricsEnabled = true;

        /**
         * 是否启用 tenant quota 事件指标。
         */
        private boolean quotaEventMetricsEnabled = true;

        private Map<String, ModelPrice> modelPrice = new HashMap<>();
    }

    @Data
    public static class Routing {

        /**
         * 是否启用失败回退（主路由失败时尝试候选 provider）
         */
        private boolean fallbackEnabled = false;

        /**
         * provider 优先级顺序，首个可用 provider 作为主路由
         */
        private List<String> providerPriority = new ArrayList<>(List.of("openai", "claude"));

        /**
         * 是否启用 A/B 灰度路由
         */
        private boolean abEnabled = false;

        /**
         * 灰度 provider（B 组）
         */
        private String abProvider = "claude";

        /**
         * B 组流量百分比（0-100）
         */
        private Integer abPercentage = 0;
    }

    @Data
    public static class Security {

        /**
         * 是否启用 API 鉴权
         */
        private boolean enabled = false;

        /**
         * 登录会话有效期（分钟）
         */
        private Long sessionTtlMinutes = 120L;

        /**
         * JWT 签名密钥（HS256，建议32字节以上）
         */
        private String jwtSecret = "replace-with-at-least-32-char-secret-key";

        /**
         * JWT issuer
         */
        private String jwtIssuer = "ai-gateway-console";

        /**
         * 用户列表（用户名 -> 凭证）
         */
        private Map<String, UserCredential> users = new HashMap<>(Map.of(
                "admin", new UserCredential("admin123456", "admin"),
                "viewer", new UserCredential("viewer123456", "viewer")
        ));

        /**
         * 写操作允许的角色
         */
        private List<String> writeRoles = new ArrayList<>(List.of("admin"));
    }

    @Data
    public static class Tenant {

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
    }

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

    @Data
    public static class UserCredential {

        private String password;

        private String role;

        public UserCredential() {
        }

        public UserCredential(String password, String role) {
            this.password = password;
            this.role = role;
        }
    }

    @Data
    public static class ModelPrice {

        /**
         * 每 1K 输入 token 单价
         */
        private Double inputPer1k = 0D;

        /**
         * 每 1K 输出 token 单价
         */
        private Double outputPer1k = 0D;
    }
}
