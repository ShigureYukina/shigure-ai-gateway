package com.nageoffer.shortlink.aigateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 网关配置门面：{@code short-link.ai-gateway.*} 的唯一绑定入口。
 * <p>
 * 四个体量较大的域（tenant / security / routing / sync）已拆到各自的属性组类，
 * 本类只保留字段声明与 yml 键位，键名逐字未变，注入点与调用方均不受影响。
 *
 * @see AiGatewayTenantProperties
 * @see AiGatewaySecurityProperties
 * @see AiGatewayRoutingProperties
 * @see AiGatewaySyncProperties
 * @see AiGatewayRuntimeConfigProperties
 */
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
    private AiGatewayRoutingProperties routing = new AiGatewayRoutingProperties();

    @Valid
    private AiGatewaySecurityProperties security = new AiGatewaySecurityProperties();

    @Valid
    private AiGatewayTenantProperties tenant = new AiGatewayTenantProperties();

    /**
     * 上游元数据同步：模型价格与模型清单。
     */
    @Valid
    private AiGatewaySyncProperties sync = new AiGatewaySyncProperties();

    /**
     * 运行时配置中心：把控制台能改的那几个域落库并同步到所有实例。
     */
    @Valid
    private AiGatewayRuntimeConfigProperties runtimeConfig = new AiGatewayRuntimeConfigProperties();

    /**
     * 主动探测：定时探活各渠道，连续失败自动禁用、恢复后自动放回候选集。
     */
    @Valid
    private AiGatewayProbeProperties probe = new AiGatewayProbeProperties();

    @Data
    public static class Upstream {

        @NotBlank
        private String defaultProvider = "openai";

        private Map<String, String> providerBaseUrl = new HashMap<>();

        /**
         * 各 provider 的聊天补全路径。缺省回退到 /v1/chat/completions；
         * Claude 等非 OpenAI 协议的上游需要显式配置（如 /v1/messages）。
         */
        private Map<String, String> providerChatPath = new HashMap<>();

        /**
         * 平台级上游凭证，key 为 provider。租户级 BYOK 未命中时回退到此处。
         */
        private Map<String, ProviderCredential> providerCredentials = new HashMap<>();

        private Map<String, String> modelAlias = new HashMap<>();
    }

    /**
     * 渠道 Key 池里的一把 Key。
     */
    @Data
    public static class ProviderApiKey {

        /**
         * 可选标识，缺省按序号生成；只用于日志与熔断状态追踪，不会发给上游。
         */
        private String keyId;

        private String apiKey;

        /**
         * 权重，越大被选中的概率越高。
         */
        private Integer weight = 1;

        private boolean enabled = true;
    }

    /**
     * 上游凭证与鉴权方式。网关据此替换请求头，客户端传入的 Authorization 不再透传给上游。
     */
    @Data
    public static class ProviderCredential {

        /**
         * 上游 API Key。
         */
        private String apiKey;

        /**
         * 承载凭证的请求头，例如 Authorization / x-api-key。
         */
        private String authHeader = "Authorization";

        /**
         * 认证 scheme，例如 Bearer；留空表示直接放置原始 Key。
         */
        private String authScheme = "Bearer";

        /**
         * 上游要求的附加请求头，例如 anthropic-version。
         */
        private Map<String, String> extraHeaders = new HashMap<>();

        private boolean enabled = true;

        /**
         * Key 池：同一渠道可以配多把 Key 轮换使用。
         * <p>
         * 留空时继续用上面的 {@code apiKey}（保持单 Key 配置兼容）；
         * 一旦配置了池，就以池为准——两处都写只会让人分不清哪把真正生效。
         */
        private List<ProviderApiKey> apiKeys = new ArrayList<>();

        /**
         * 渠道级每分钟请求上限（RPM），null 或 &le;0 表示不限。
         * <p>
         * 超限按"该通道暂时不可用"处理：回退链会换一条通道继续，
         * 所有通道都被限住才把 429 返回给客户端。
         */
        private Integer rpmLimit;
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

        /**
         * 配额超限时返回给客户端的 Retry-After 秒数。
         */
        private Long quotaRetryAfterSeconds = 60L;

        // 默认只留 ip：userId/consumer 维度已改取服务端身份（keyId/appId，本就在键前缀里），
        // 客户端头里的同名维度是可伪造的，不再默认参与配额身份
        private List<String> keyDimensions = new ArrayList<>(List.of("ip"));
    }

    @Data
    public static class Cache {

        private boolean enabled = false;

        private Duration ttl = Duration.ofMinutes(2);

        private boolean semanticCacheEnabled = false;

        /**
         * 语义缓存相似度阈值，使用 trigram Jaccard 计算。
         */
        private Double semanticSimilarityThreshold = 0.85D;

        /**
         * 语义缓存索引最大条目数，超出后按写入时间裁剪最旧条目，避免 ZSet 无界增长。
         */
        private Integer semanticIndexMaxEntries = 1000;

        /**
         * 参与语义索引的单条文本最大字符数，超长截断。
         */
        private Integer semanticIndexMaxContentLength = 2048;
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

        /**
         * 是否启用分布式追踪。
         */
        private boolean tracingEnabled = true;

        /**
         * 追踪采样率 0.0-1.0。
         */
        private Double tracingSamplingRate = 1.0D;

        /**
         * 是否开启实时请求链路事件（SSE 看板）。
         * <p>
         * 关闭后埋点直接短路，连事件对象都不构造。
         */
        private boolean traceStreamEnabled = true;

        /**
         * 实时链路保留的最近事件条数，供页面首屏加载。
         */
        private int traceRecentLimit = 200;

        private Map<String, ModelPrice> modelPrice = new HashMap<>();
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
