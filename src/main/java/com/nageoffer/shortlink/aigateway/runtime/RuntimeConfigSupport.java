package com.nageoffer.shortlink.aigateway.runtime;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayRoutingProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 六个配置域的 extract / apply：{@code AiGatewayProperties} ⇄ JSON 友好的 Map。
 * <p>
 * 为什么不做"整个域对象序列化再反序列化替换"：{@code security} 域里还有 {@code jwtSecret} 与 {@code users}、
 * {@code upstream} 域里还有平台级 {@code providerCredentials}——这些键不会出现在快照里，
 * 反序列化时会落回字段初始值，等于**把 yml 里的密钥和口令清空**。所以这里只搬运"控制台确实能改"的字段，
 * 逐字段 set，库里缺的键一律保持 yml 原值。
 * <p>
 * 另一条刻意的取舍：快照里不出现 {@code Duration} 与枚举对象，而是 {@code ttlSeconds}（Long）与
 * {@code routingStrategy}（枚举 name）。这样全链路只需要处理 JSON 原生类型，
 * 不必为了反序列化去注册 {@code JavaTimeModule} —— 测试里惯用的 {@code new ObjectMapper()} 并没有它。
 */
public final class RuntimeConfigSupport {

    /**
     * 安全域强制开启开关（环境变量或同名系统属性）。
     * <p>
     * 存在的原因是反向失败：库里的行写着 {@code enabled=true}，但重启时 DB 不可用 →
     * 加载失败 → 回落到 yml 的 {@code false} → 管理面静默裸奔。
     * 三处优先级：本开关 &gt; DB &gt; yml。
     */
    public static final String SECURITY_FORCE_ENABLED_KEY = "AI_GATEWAY_SECURITY_FORCE_ENABLED";

    private RuntimeConfigSupport() {
    }

    /**
     * 把当前内存值抽成快照。只包含"运行时可变更"的键。
     */
    public static Map<String, Object> extract(RuntimeConfigDomain domain, AiGatewayProperties properties) {
        Map<String, Object> values = new LinkedHashMap<>();
        switch (domain) {
            case ROUTING -> extractRouting(properties, values);
            case RATE_LIMIT -> extractRateLimit(properties, values);
            case CACHE -> extractCache(properties, values);
            case SAFETY -> extractSafety(properties, values);
            case PLUGIN -> values.put("pluginEnabledMap", new LinkedHashMap<>(properties.getPlugin().getPluginEnabledMap()));
            case SECURITY -> extractSecurity(properties, values);
        }
        return values;
    }

    /**
     * 把快照应用到内存。缺键 = 保持 yml 原值（这是"库里只存改过的域"能work的前提）。
     */
    public static void apply(RuntimeConfigDomain domain, AiGatewayProperties properties, Map<String, Object> values) {
        apply(domain, properties, values, securityForceEnabled());
    }

    static void apply(RuntimeConfigDomain domain,
                      AiGatewayProperties properties,
                      Map<String, Object> values,
                      boolean securityForced) {
        if (values == null || values.isEmpty()) {
            return;
        }
        switch (domain) {
            case ROUTING -> applyRouting(properties, values);
            case RATE_LIMIT -> applyRateLimit(properties, values);
            case CACHE -> applyCache(properties, values);
            case SAFETY -> applySafety(properties, values);
            case PLUGIN -> applyPlugin(properties, values);
            case SECURITY -> applySecurity(properties, values, securityForced);
        }
    }

    public static boolean securityForceEnabled() {
        String raw = System.getProperty(SECURITY_FORCE_ENABLED_KEY);
        if (!StringUtils.hasText(raw)) {
            raw = System.getenv(SECURITY_FORCE_ENABLED_KEY);
        }
        return raw != null && Boolean.parseBoolean(raw.trim());
    }

    // ------------------------------------------------------------------ routing

    private static void extractRouting(AiGatewayProperties properties, Map<String, Object> values) {
        AiGatewayRoutingProperties routing = properties.getRouting();
        values.put("fallbackEnabled", routing.isFallbackEnabled());
        values.put("providerPriority", new ArrayList<>(routing.getProviderPriority()));
        values.put("abEnabled", routing.isAbEnabled());
        values.put("abProvider", routing.getAbProvider());
        values.put("abPercentage", routing.getAbPercentage());
        values.put("dynamicRoutingEnabled", routing.isDynamicRoutingEnabled());
        values.put("routingStrategy", routing.getRoutingStrategy().name());
        // 通道组 / 模型绑定刻意不进来：它们的真源是 provider_group 系列表（ProviderGroupService），
        // 塞进配置域会出现"两个真源互相覆盖"。
        values.put("providerBaseUrl", new LinkedHashMap<>(properties.getUpstream().getProviderBaseUrl()));
        values.put("modelAlias", new LinkedHashMap<>(properties.getUpstream().getModelAlias()));
    }

    private static void applyRouting(AiGatewayProperties properties, Map<String, Object> values) {
        AiGatewayRoutingProperties routing = properties.getRouting();

        Boolean fallbackEnabled = asBoolean(values.get("fallbackEnabled"));
        if (fallbackEnabled != null) {
            routing.setFallbackEnabled(fallbackEnabled);
        }
        List<String> providerPriority = asStringList(values.get("providerPriority"));
        if (providerPriority != null && !providerPriority.isEmpty()) {
            routing.setProviderPriority(providerPriority);
        }
        Boolean abEnabled = asBoolean(values.get("abEnabled"));
        if (abEnabled != null) {
            routing.setAbEnabled(abEnabled);
        }
        String abProvider = asString(values.get("abProvider"));
        if (abProvider != null) {
            routing.setAbProvider(abProvider);
        }
        Integer abPercentage = asInteger(values.get("abPercentage"));
        if (abPercentage != null) {
            routing.setAbPercentage(Math.max(0, Math.min(100, abPercentage)));
        }
        Boolean dynamicRoutingEnabled = asBoolean(values.get("dynamicRoutingEnabled"));
        if (dynamicRoutingEnabled != null) {
            routing.setDynamicRoutingEnabled(dynamicRoutingEnabled);
        }
        String routingStrategy = asString(values.get("routingStrategy"));
        if (routingStrategy != null) {
            try {
                routing.setRoutingStrategy(AiGatewayRoutingProperties.RoutingStrategy.valueOf(routingStrategy.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // 库里存了不认识的枚举名：保留 yml 值，比让整次加载失败好；运维能通过 GET 看到库里到底存了什么
            }
        }
        Map<String, String> providerBaseUrl = asStringMap(values.get("providerBaseUrl"));
        if (providerBaseUrl != null) {
            properties.getUpstream().setProviderBaseUrl(providerBaseUrl);
        }
        Map<String, String> modelAlias = asStringMap(values.get("modelAlias"));
        if (modelAlias != null) {
            properties.getUpstream().setModelAlias(modelAlias);
        }
    }

    // --------------------------------------------------------------- rateLimit

    private static void extractRateLimit(AiGatewayProperties properties, Map<String, Object> values) {
        values.put("enabled", properties.getRateLimit().isEnabled());
        values.put("defaultTokenQuotaPerMinute", properties.getRateLimit().getDefaultTokenQuotaPerMinute());
        values.put("defaultTokenQuotaPerDay", properties.getRateLimit().getDefaultTokenQuotaPerDay());
        values.put("minTokenReserve", properties.getRateLimit().getMinTokenReserve());
        values.put("quotaRetryAfterSeconds", properties.getRateLimit().getQuotaRetryAfterSeconds());
        values.put("keyDimensions", new ArrayList<>(properties.getRateLimit().getKeyDimensions()));
    }

    private static void applyRateLimit(AiGatewayProperties properties, Map<String, Object> values) {
        Boolean enabled = asBoolean(values.get("enabled"));
        if (enabled != null) {
            properties.getRateLimit().setEnabled(enabled);
        }
        Long minuteQuota = asLong(values.get("defaultTokenQuotaPerMinute"));
        if (minuteQuota != null && minuteQuota > 0) {
            properties.getRateLimit().setDefaultTokenQuotaPerMinute(minuteQuota);
        }
        Long dayQuota = asLong(values.get("defaultTokenQuotaPerDay"));
        if (dayQuota != null && dayQuota > 0) {
            properties.getRateLimit().setDefaultTokenQuotaPerDay(dayQuota);
        }
        Long minTokenReserve = asLong(values.get("minTokenReserve"));
        if (minTokenReserve != null && minTokenReserve > 0) {
            properties.getRateLimit().setMinTokenReserve(minTokenReserve);
        }
        Long quotaRetryAfterSeconds = asLong(values.get("quotaRetryAfterSeconds"));
        if (quotaRetryAfterSeconds != null && quotaRetryAfterSeconds > 0) {
            properties.getRateLimit().setQuotaRetryAfterSeconds(quotaRetryAfterSeconds);
        }
        List<String> keyDimensions = asStringList(values.get("keyDimensions"));
        if (keyDimensions != null && !keyDimensions.isEmpty()) {
            properties.getRateLimit().setKeyDimensions(keyDimensions);
        }
    }

    // ------------------------------------------------------------------- cache

    private static void extractCache(AiGatewayProperties properties, Map<String, Object> values) {
        values.put("enabled", properties.getCache().isEnabled());
        values.put("ttlSeconds", properties.getCache().getTtl().toSeconds());
        values.put("semanticCacheEnabled", properties.getCache().isSemanticCacheEnabled());
        values.put("semanticSimilarityThreshold", properties.getCache().getSemanticSimilarityThreshold());
        values.put("semanticIndexMaxEntries", properties.getCache().getSemanticIndexMaxEntries());
        values.put("semanticIndexMaxContentLength", properties.getCache().getSemanticIndexMaxContentLength());
    }

    private static void applyCache(AiGatewayProperties properties, Map<String, Object> values) {
        Boolean enabled = asBoolean(values.get("enabled"));
        if (enabled != null) {
            properties.getCache().setEnabled(enabled);
        }
        Long ttlSeconds = asLong(values.get("ttlSeconds"));
        if (ttlSeconds != null && ttlSeconds > 0) {
            properties.getCache().setTtl(Duration.ofSeconds(ttlSeconds));
        }
        Boolean semanticCacheEnabled = asBoolean(values.get("semanticCacheEnabled"));
        if (semanticCacheEnabled != null) {
            properties.getCache().setSemanticCacheEnabled(semanticCacheEnabled);
        }
        Double similarityThreshold = asDouble(values.get("semanticSimilarityThreshold"));
        if (similarityThreshold != null && similarityThreshold > 0 && similarityThreshold <= 1) {
            properties.getCache().setSemanticSimilarityThreshold(similarityThreshold);
        }
        Integer maxEntries = asInteger(values.get("semanticIndexMaxEntries"));
        if (maxEntries != null && maxEntries > 0) {
            properties.getCache().setSemanticIndexMaxEntries(maxEntries);
        }
        Integer maxContentLength = asInteger(values.get("semanticIndexMaxContentLength"));
        if (maxContentLength != null && maxContentLength > 0) {
            properties.getCache().setSemanticIndexMaxContentLength(maxContentLength);
        }
    }

    // ------------------------------------------------------------------ safety

    private static void extractSafety(AiGatewayProperties properties, Map<String, Object> values) {
        values.put("enabled", properties.getSafety().isEnabled());
        values.put("inputStrategy", properties.getSafety().getInputStrategy());
        values.put("outputStrategy", properties.getSafety().getOutputStrategy());
        values.put("blockedWords", new ArrayList<>(properties.getSafety().getBlockedWords()));
        values.put("promptInjectionPatterns", new ArrayList<>(properties.getSafety().getPromptInjectionPatterns()));
        values.put("piiPatterns", new ArrayList<>(properties.getSafety().getPiiPatterns()));
        values.put("redactMask", properties.getSafety().getRedactMask());
    }

    private static void applySafety(AiGatewayProperties properties, Map<String, Object> values) {
        Boolean enabled = asBoolean(values.get("enabled"));
        if (enabled != null) {
            properties.getSafety().setEnabled(enabled);
        }
        String inputStrategy = asString(values.get("inputStrategy"));
        if (inputStrategy != null) {
            properties.getSafety().setInputStrategy(inputStrategy);
        }
        String outputStrategy = asString(values.get("outputStrategy"));
        if (outputStrategy != null) {
            properties.getSafety().setOutputStrategy(outputStrategy);
        }
        // blockedWords / 两类 patterns 允许被清空 —— "把词表清掉"是一个合法动作，
        // 不像 providerPriority 那种空列表会直接让路由失去意义。
        List<String> blockedWords = asStringList(values.get("blockedWords"));
        if (values.containsKey("blockedWords") && blockedWords != null) {
            properties.getSafety().setBlockedWords(new LinkedHashSet<>(blockedWords));
        }
        List<String> promptInjectionPatterns = asStringList(values.get("promptInjectionPatterns"));
        if (values.containsKey("promptInjectionPatterns") && promptInjectionPatterns != null) {
            properties.getSafety().setPromptInjectionPatterns(promptInjectionPatterns);
        }
        List<String> piiPatterns = asStringList(values.get("piiPatterns"));
        if (values.containsKey("piiPatterns") && piiPatterns != null) {
            properties.getSafety().setPiiPatterns(piiPatterns);
        }
        String redactMask = asString(values.get("redactMask"));
        if (redactMask != null) {
            properties.getSafety().setRedactMask(redactMask);
        }
    }

    // ------------------------------------------------------------------ plugin

    private static void applyPlugin(AiGatewayProperties properties, Map<String, Object> values) {
        Map<String, Object> raw = asObjectMap(values.get("pluginEnabledMap"));
        if (raw == null) {
            return;
        }
        Map<String, Boolean> enabledMap = new LinkedHashMap<>();
        raw.forEach((name, flag) -> {
            Boolean parsed = asBoolean(flag);
            if (parsed != null) {
                enabledMap.put(name, parsed);
            }
        });
        properties.getPlugin().getPluginEnabledMap().clear();
        properties.getPlugin().getPluginEnabledMap().putAll(enabledMap);
    }

    // ---------------------------------------------------------------- security

    private static void extractSecurity(AiGatewayProperties properties, Map<String, Object> values) {
        // 只有这两个键：jwtSecret / users / jwtIssuer / sessionTtlMinutes 属于"部署期密钥"，
        // 落库等于把口令写进业务表，也让轮换密钥必须走 DB 而不是改环境变量。
        values.put("enabled", properties.getSecurity().isEnabled());
        values.put("writeRoles", new ArrayList<>(properties.getSecurity().getWriteRoles()));
    }

    private static void applySecurity(AiGatewayProperties properties, Map<String, Object> values, boolean securityForced) {
        if (securityForced) {
            properties.getSecurity().setEnabled(true);
        } else {
            Boolean enabled = asBoolean(values.get("enabled"));
            if (enabled != null) {
                properties.getSecurity().setEnabled(enabled);
            }
        }
        List<String> writeRoles = asStringList(values.get("writeRoles"));
        if (writeRoles != null && !writeRoles.isEmpty()) {
            properties.getSecurity().setWriteRoles(writeRoles);
        }
    }

    // ----------------------------------------------------------------- helpers

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Boolean.parseBoolean(text.trim());
        }
        return null;
    }

    private static String asString(Object value) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        return null;
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object each : list) {
            if (each != null) {
                result.add(String.valueOf(each));
            }
        }
        return result;
    }

    private static Map<String, String> asStringMap(Object value) {
        Map<String, Object> raw = asObjectMap(value);
        if (raw == null) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, each) -> {
            if (key != null && each != null) {
                result.put(key, String.valueOf(each));
            }
        });
        return result;
    }

    private static Map<String, Object> asObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, each) -> {
            if (key != null) {
                result.put(String.valueOf(key), each);
            }
        });
        return result;
    }
}
