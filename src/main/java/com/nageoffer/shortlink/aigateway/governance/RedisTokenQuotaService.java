package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTenantProperties;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RedisTokenQuotaService {

    private static final String QUOTA_PREFIX = "short-link:ai-gateway:quota:";

    /**
     * 预检脚本：先判断三个维度的配额是否够，够才扣减并设置 TTL。
     * 作为静态常量复用，避免每次请求都重新构造脚本对象。
     */
    private static final DefaultRedisScript<Long> TOKEN_QUOTA_SCRIPT = new DefaultRedisScript<>("""
            local minuteCurrent = tonumber(redis.call('GET', KEYS[1]) or '0')
            local dayCurrent = tonumber(redis.call('GET', KEYS[2]) or '0')
            local monthCurrent = tonumber(redis.call('GET', KEYS[3]) or '0')
            local delta = tonumber(ARGV[1])
            local minuteLimit = tonumber(ARGV[2])
            local dayLimit = tonumber(ARGV[3])
            local monthLimit = tonumber(ARGV[4])
            local minuteTtl = tonumber(ARGV[5])
            local dayTtl = tonumber(ARGV[6])
            local monthTtl = tonumber(ARGV[7])

            if (minuteCurrent + delta > minuteLimit) then
                return 0
            end
            if (dayCurrent + delta > dayLimit) then
                return 0
            end
            if (monthCurrent + delta > monthLimit) then
                return 0
            end

            local minuteNew = redis.call('INCRBY', KEYS[1], delta)
            if minuteNew == delta then
                redis.call('EXPIRE', KEYS[1], minuteTtl)
            end

            local dayNew = redis.call('INCRBY', KEYS[2], delta)
            if dayNew == delta then
                redis.call('EXPIRE', KEYS[2], dayTtl)
            end

            local monthNew = redis.call('INCRBY', KEYS[3], delta)
            if monthNew == delta then
                redis.call('EXPIRE', KEYS[3], monthTtl)
            end

            return 1
            """, Long.class);

    /**
     * 补偿脚本：实际用量低于预留时归还不超过当前值的差额，并保留原有 TTL。
     */
    private static final DefaultRedisScript<Long> QUOTA_COMPENSATION_SCRIPT = new DefaultRedisScript<>("""
            local refund = tonumber(ARGV[1])
            local minuteCurrent = tonumber(redis.call('GET', KEYS[1]) or '0')
            local dayCurrent = tonumber(redis.call('GET', KEYS[2]) or '0')
            local monthCurrent = tonumber(redis.call('GET', KEYS[3]) or '0')
            local minuteTtl = redis.call('TTL', KEYS[1])
            local dayTtl = redis.call('TTL', KEYS[2])
            local monthTtl = redis.call('TTL', KEYS[3])

            local minuteNew = minuteCurrent - refund
            if minuteNew < 0 then
                minuteNew = 0
            end
            local dayNew = dayCurrent - refund
            if dayNew < 0 then
                dayNew = 0
            end
            local monthNew = monthCurrent - refund
            if monthNew < 0 then
                monthNew = 0
            end

            if minuteTtl > 0 then
                redis.call('SET', KEYS[1], minuteNew, 'EX', minuteTtl)
            elseif minuteTtl == -1 then
                redis.call('SET', KEYS[1], minuteNew)
            elseif minuteNew > 0 then
                redis.call('SET', KEYS[1], minuteNew)
            end

            if dayTtl > 0 then
                redis.call('SET', KEYS[2], dayNew, 'EX', dayTtl)
            elseif dayTtl == -1 then
                redis.call('SET', KEYS[2], dayNew)
            elseif dayNew > 0 then
                redis.call('SET', KEYS[2], dayNew)
            end

            if monthTtl > 0 then
                redis.call('SET', KEYS[3], monthNew, 'EX', monthTtl)
            elseif monthTtl == -1 then
                redis.call('SET', KEYS[3], monthNew)
            elseif monthNew > 0 then
                redis.call('SET', KEYS[3], monthNew)
            end

            return 1
            """, Long.class);

    /**
     * 结算脚本：按实际用量与预扣的差值补扣或返还，并回传三个窗口的结算后用量。
     * <p>
     * 合并成一次往返的原因：结算处在每请求终态，原先"差额为正时发三条 INCRBY"会带来
     * 额外三次 RTT；同时回传结算后用量，才能对外给出可信的限流剩余量响应头。
     */
    private static final DefaultRedisScript<List> QUOTA_SETTLE_SCRIPT = new DefaultRedisScript<>("""
            local delta = tonumber(ARGV[1])
            local function apply(key)
                local current = tonumber(redis.call('GET', key) or '0')
                local ttl = redis.call('TTL', key)
                local updated = current + delta
                if updated < 0 then
                    updated = 0
                end
                if ttl > 0 then
                    redis.call('SET', key, updated, 'EX', ttl)
                elseif ttl == -1 then
                    redis.call('SET', key, updated)
                elseif updated > 0 then
                    redis.call('SET', key, updated)
                end
                return updated
            end
            return {apply(KEYS[1]), apply(KEYS[2]), apply(KEYS[3])}
            """, List.class);

    private final ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    private final TokenEstimator tokenEstimator;

    private final QuotaKeyGenerator quotaKeyGenerator;

    private final AiGatewayProperties properties;

    private final AiGatewayMetricsRecorder metricsRecorder;

    private final TenantConfigQueryService tenantConfigQueryService;

    @Autowired
    public RedisTokenQuotaService(ReactiveStringRedisTemplate reactiveStringRedisTemplate,
                                  TokenEstimator tokenEstimator,
                                  QuotaKeyGenerator quotaKeyGenerator,
                                  AiGatewayProperties properties,
                                  AiGatewayMetricsRecorder metricsRecorder,
                                  TenantConfigQueryService tenantConfigQueryService) {
        this.reactiveStringRedisTemplate = reactiveStringRedisTemplate;
        this.tokenEstimator = tokenEstimator;
        this.quotaKeyGenerator = quotaKeyGenerator;
        this.properties = properties;
        this.metricsRecorder = metricsRecorder;
        this.tenantConfigQueryService = tenantConfigQueryService;
    }

    public RedisTokenQuotaService(ReactiveStringRedisTemplate reactiveStringRedisTemplate,
                                  TokenEstimator tokenEstimator,
                                  QuotaKeyGenerator quotaKeyGenerator,
                                  AiGatewayProperties properties,
                                  AiGatewayMetricsRecorder metricsRecorder) {
        this(reactiveStringRedisTemplate, tokenEstimator, quotaKeyGenerator, properties, metricsRecorder, TenantConfigQueryService.fallbackOnly(properties));
    }

    /**
     * 配额预检：预扣 token。配额不足时以 {@link AiGatewayErrorCode#QUOTA_EXCEEDED} 错误结束。
     * <p>
     * 返回 {@link Mono} 而非同步对象，避免阻塞 Netty 事件循环。
     */
    public Mono<QuotaPreCheckContext> preCheck(TenantContext tenantContext, HttpHeaders headers, String provider, String providerModel, AiChatCompletionReqDTO request) {
        if (!properties.getRateLimit().isEnabled()) {
            return Mono.just(QuotaPreCheckContext.builder().reservedTokens(0).build());
        }
        TokenEstimateResult estimate = tokenEstimator.estimate(request);
        long reserve = estimate.totalReserve();
        String quotaKey = quotaKeyGenerator.build(tenantContext, headers, provider, providerModel);
        String minuteKey = QUOTA_PREFIX + "minute:" + quotaKey;
        String dayKey = QUOTA_PREFIX + "day:" + LocalDate.now(ZoneId.systemDefault()) + ":" + quotaKey;
        String monthKey = QUOTA_PREFIX + "month:" + YearMonth.now(ZoneId.systemDefault()) + ":" + quotaKey;
        TenantQuotaConfig quotaConfig = resolveQuotaConfig(tenantContext);
        long minuteQuota = quotaConfig.minuteQuota();
        long dayQuota = quotaConfig.dayQuota();
        long monthQuota = quotaConfig.monthQuota();
        long minuteTtl = Duration.ofMinutes(1).toSeconds();
        long dayTtl = Duration.ofDays(2).toSeconds();
        long monthTtl = Duration.ofDays(32).toSeconds();

        List<String> keys = List.of(minuteKey, dayKey, monthKey);
        List<String> args = List.of(
                String.valueOf(reserve),
                String.valueOf(minuteQuota),
                String.valueOf(dayQuota),
                String.valueOf(monthQuota),
                String.valueOf(minuteTtl),
                String.valueOf(dayTtl),
                String.valueOf(monthTtl));

        return reactiveStringRedisTemplate.execute(TOKEN_QUOTA_SCRIPT, keys, args)
                .next()
                .defaultIfEmpty(0L)
                .flatMap(allowed -> {
                    if (allowed == 0L) {
                        metricsRecorder.recordTenantQuotaEvent(tenantIdOf(tenantContext), appIdOf(tenantContext), provider, providerModel, "reject", reserve);
                        return Mono.error(new AiGatewayClientException(AiGatewayErrorCode.QUOTA_EXCEEDED, "Token 配额不足，已触发限流"));
                    }
                    metricsRecorder.recordTenantQuotaEvent(tenantIdOf(tenantContext), appIdOf(tenantContext), provider, providerModel, "reserve", reserve);
                    return Mono.just(QuotaPreCheckContext.builder()
                            .quotaKey(quotaKey)
                            .tenantId(tenantIdOf(tenantContext))
                            .appId(appIdOf(tenantContext))
                            .provider(provider)
                            .providerModel(providerModel)
                            .reservedTokens(reserve)
                            .minuteQuota(minuteQuota)
                            .dayQuota(dayQuota)
                            .monthQuota(monthQuota)
                            .minuteKey(minuteKey)
                            .dayKey(dayKey)
                            .monthKey(monthKey)
                            .build());
                });
    }

    /**
     * 按实际用量结算配额：多用则补扣，少用则按差额返还。
     * <p>
     * 返回结算后各窗口的用量，供响应头与观测使用。
     */
    public Mono<QuotaSettleResult> adjustByActualUsage(QuotaPreCheckContext context, long actualTotalTokens) {
        if (context == null || context.getReservedTokens() <= 0 || actualTotalTokens <= 0) {
            return Mono.empty();
        }
        long diff = actualTotalTokens - context.getReservedTokens();
        if (diff == 0) {
            return Mono.empty();
        }
        return reactiveStringRedisTemplate.execute(QUOTA_SETTLE_SCRIPT,
                        List.of(context.getMinuteKey(), context.getDayKey(), context.getMonthKey()),
                        List.of(String.valueOf(diff)))
                .next()
                .map(this::toSettleResult);
    }

    @SuppressWarnings("unchecked")
    private QuotaSettleResult toSettleResult(Object scriptResult) {
        if (!(scriptResult instanceof List<?> values) || values.size() < 3) {
            return null;
        }
        List<Object> numbers = (List<Object>) values;
        return new QuotaSettleResult(parseLongOrZero(numbers.get(0)), parseLongOrZero(numbers.get(1)), parseLongOrZero(numbers.get(2)));
    }

    private long parseLongOrZero(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * 配额结算结果：三个窗口在结算后的实际用量。
     */
    public record QuotaSettleResult(long minuteUsed, long dayUsed, long monthUsed) {
    }

    /**
     * 释放预扣配额。
     * <p>
     * 请求失败或客户端取消时，上游没有可采信的实际用量，必须把预扣额度还回去；
     * 否则失败请求会永久占用配额，把租户的可用额度慢慢吃掉。
     */
    public Mono<Void> release(QuotaPreCheckContext context) {
        if (context == null || context.getReservedTokens() <= 0) {
            return Mono.empty();
        }
        metricsRecorder.recordTenantQuotaEvent(tenantIdOf(context), appIdOf(context), context.getProvider(),
                context.getProviderModel(), "release", context.getReservedTokens());
        return reactiveStringRedisTemplate.execute(QUOTA_COMPENSATION_SCRIPT,
                        List.of(context.getMinuteKey(), context.getDayKey(), context.getMonthKey()),
                        List.of(String.valueOf(context.getReservedTokens())))
                .then();
    }

    public Mono<Map<String, Object>> currentUsage(HttpHeaders headers, String provider, String providerModel) {
        return currentUsage(null, headers, provider, providerModel);
    }

    public Mono<Map<String, Object>> currentUsage(TenantContext tenantContext, HttpHeaders headers, String provider, String providerModel) {
        String quotaKey = quotaKeyGenerator.build(tenantContext, headers, provider, providerModel);
        String minuteKey = QUOTA_PREFIX + "minute:" + quotaKey;
        String dayKey = QUOTA_PREFIX + "day:" + LocalDate.now(ZoneId.systemDefault()) + ":" + quotaKey;
        String monthKey = QUOTA_PREFIX + "month:" + YearMonth.now(ZoneId.systemDefault()) + ":" + quotaKey;

        TenantQuotaConfig quotaConfig = resolveQuotaConfig(tenantContext);
        long minuteQuota = quotaConfig.minuteQuota();
        long dayQuota = quotaConfig.dayQuota();
        long monthQuota = quotaConfig.monthQuota();

        return Mono.zip(
                        reactiveStringRedisTemplate.opsForValue().get(minuteKey).defaultIfEmpty("0"),
                        reactiveStringRedisTemplate.opsForValue().get(dayKey).defaultIfEmpty("0"),
                        reactiveStringRedisTemplate.opsForValue().get(monthKey).defaultIfEmpty("0"))
                .map(tuple -> {
                    long minuteUsed = parseLongOrZero(tuple.getT1());
                    long dayUsed = parseLongOrZero(tuple.getT2());
                    long monthUsed = parseLongOrZero(tuple.getT3());

                    Map<String, Object> usage = new LinkedHashMap<>();
                    usage.put("enabled", properties.getRateLimit().isEnabled());
                    usage.put("provider", provider);
                    usage.put("model", providerModel);
                    usage.put("quotaKey", quotaKey);
                    usage.put("minuteQuota", minuteQuota);
                    usage.put("minuteUsed", minuteUsed);
                    usage.put("minuteRemaining", Math.max(0L, minuteQuota - minuteUsed));
                    usage.put("dayQuota", dayQuota);
                    usage.put("dayUsed", dayUsed);
                    usage.put("dayRemaining", Math.max(0L, dayQuota - dayUsed));
                    usage.put("monthQuota", monthQuota);
                    usage.put("monthUsed", monthUsed);
                    usage.put("monthRemaining", Math.max(0L, monthQuota - monthUsed));
                    return usage;
                });
    }

    private TenantQuotaConfig resolveQuotaConfig(TenantContext tenantContext) {
        long minuteQuota = properties.getRateLimit().getDefaultTokenQuotaPerMinute();
        long dayQuota = properties.getRateLimit().getDefaultTokenQuotaPerDay();
        long monthQuota = Math.max(dayQuota, dayQuota * 30);
        if (tenantContext == null) {
            return new TenantQuotaConfig(minuteQuota, dayQuota, monthQuota);
        }
        AiGatewayTenantProperties.TenantQuotaPolicy quotaPolicy = tenantConfigQueryService.findQuotaPolicy(tenantContext.tenantId()).orElse(null);
        if (quotaPolicy == null || !quotaPolicy.isEnabled()) {
            return new TenantQuotaConfig(minuteQuota, dayQuota, monthQuota);
        }
        return new TenantQuotaConfig(
                quotaPolicy.getTokenQuotaPerMinute() == null ? minuteQuota : quotaPolicy.getTokenQuotaPerMinute(),
                quotaPolicy.getTokenQuotaPerDay() == null ? dayQuota : quotaPolicy.getTokenQuotaPerDay(),
                quotaPolicy.getTokenQuotaPerMonth() == null ? monthQuota : quotaPolicy.getTokenQuotaPerMonth()
        );
    }

    private long parseLongOrZero(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private String tenantIdOf(TenantContext tenantContext) {
        return tenantContext == null ? properties.getTenant().getDefaultTenantId() : tenantContext.tenantId();
    }

    private String tenantIdOf(QuotaPreCheckContext context) {
        return context.getTenantId() == null ? properties.getTenant().getDefaultTenantId() : context.getTenantId();
    }

    private String appIdOf(QuotaPreCheckContext context) {
        return context.getAppId() == null ? properties.getTenant().getDefaultAppId() : context.getAppId();
    }

    private String appIdOf(TenantContext tenantContext) {
        return tenantContext == null ? properties.getTenant().getDefaultAppId() : tenantContext.appId();
    }

    private record TenantQuotaConfig(long minuteQuota, long dayQuota, long monthQuota) {
    }
}
