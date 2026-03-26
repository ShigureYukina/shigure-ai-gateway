package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Component
public class RedisTokenQuotaService {

    private static final String QUOTA_PREFIX = "short-link:ai-gateway:quota:";

    private final StringRedisTemplate stringRedisTemplate;

    private final TokenEstimator tokenEstimator;

    private final QuotaKeyGenerator quotaKeyGenerator;

    private final AiGatewayProperties properties;

    private final AiGatewayMetricsRecorder metricsRecorder;

    private final TenantConfigQueryService tenantConfigQueryService;

    @Autowired
    public RedisTokenQuotaService(StringRedisTemplate stringRedisTemplate,
                                  TokenEstimator tokenEstimator,
                                  QuotaKeyGenerator quotaKeyGenerator,
                                  AiGatewayProperties properties,
                                  AiGatewayMetricsRecorder metricsRecorder,
                                  TenantConfigQueryService tenantConfigQueryService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.tokenEstimator = tokenEstimator;
        this.quotaKeyGenerator = quotaKeyGenerator;
        this.properties = properties;
        this.metricsRecorder = metricsRecorder;
        this.tenantConfigQueryService = tenantConfigQueryService;
    }

    public RedisTokenQuotaService(StringRedisTemplate stringRedisTemplate,
                                  TokenEstimator tokenEstimator,
                                  QuotaKeyGenerator quotaKeyGenerator,
                                  AiGatewayProperties properties,
                                  AiGatewayMetricsRecorder metricsRecorder) {
        this(stringRedisTemplate, tokenEstimator, quotaKeyGenerator, properties, metricsRecorder, TenantConfigQueryService.fallbackOnly(properties));
    }

    public QuotaPreCheckContext preCheck(TenantContext tenantContext, HttpHeaders headers, String provider, String providerModel, AiChatCompletionReqDTO request) {
        if (!properties.getRateLimit().isEnabled()) {
            return QuotaPreCheckContext.builder().reservedTokens(0).build();
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
        Long allowed = stringRedisTemplate.execute(tokenQuotaLuaScript(), List.of(minuteKey, dayKey, monthKey),
                String.valueOf(reserve),
                String.valueOf(minuteQuota),
                String.valueOf(dayQuota),
                String.valueOf(monthQuota),
                String.valueOf(minuteTtl),
                String.valueOf(dayTtl),
                String.valueOf(monthTtl));
        if (allowed == null || allowed == 0L) {
            metricsRecorder.recordTenantQuotaEvent(tenantIdOf(tenantContext), appIdOf(tenantContext), provider, providerModel, "reject", reserve);
            throw new AiGatewayClientException(AiGatewayErrorCode.QUOTA_EXCEEDED, "Token 配额不足，已触发限流");
        }
        metricsRecorder.recordTenantQuotaEvent(tenantIdOf(tenantContext), appIdOf(tenantContext), provider, providerModel, "reserve", reserve);
        return QuotaPreCheckContext.builder()
                .quotaKey(quotaKey)
                .reservedTokens(reserve)
                .minuteQuota(minuteQuota)
                .dayQuota(dayQuota)
                .monthQuota(monthQuota)
                .minuteKey(minuteKey)
                .dayKey(dayKey)
                .monthKey(monthKey)
                .build();
    }

    public void adjustByActualUsage(QuotaPreCheckContext context, long actualTotalTokens) {
        if (context == null || context.getReservedTokens() <= 0 || actualTotalTokens <= 0) {
            return;
        }
        long diff = actualTotalTokens - context.getReservedTokens();
        if (diff == 0) {
            return;
        }
        if (diff > 0) {
            stringRedisTemplate.opsForValue().increment(context.getMinuteKey(), diff);
            stringRedisTemplate.opsForValue().increment(context.getDayKey(), diff);
            stringRedisTemplate.opsForValue().increment(context.getMonthKey(), diff);
            return;
        }
        stringRedisTemplate.execute(quotaCompensationLuaScript(), List.of(context.getMinuteKey(), context.getDayKey(), context.getMonthKey()), String.valueOf(-diff));
    }

    public Map<String, Object> currentUsage(HttpHeaders headers, String provider, String providerModel) {
        return currentUsage(null, headers, provider, providerModel);
    }

    public Map<String, Object> currentUsage(TenantContext tenantContext, HttpHeaders headers, String provider, String providerModel) {
        String quotaKey = quotaKeyGenerator.build(tenantContext, headers, provider, providerModel);
        String minuteKey = QUOTA_PREFIX + "minute:" + quotaKey;
        String dayKey = QUOTA_PREFIX + "day:" + LocalDate.now(ZoneId.systemDefault()) + ":" + quotaKey;
        String monthKey = QUOTA_PREFIX + "month:" + YearMonth.now(ZoneId.systemDefault()) + ":" + quotaKey;

        TenantQuotaConfig quotaConfig = resolveQuotaConfig(tenantContext);
        long minuteQuota = quotaConfig.minuteQuota();
        long dayQuota = quotaConfig.dayQuota();
        long monthQuota = quotaConfig.monthQuota();
        long minuteUsed = parseLongOrZero(stringRedisTemplate.opsForValue().get(minuteKey));
        long dayUsed = parseLongOrZero(stringRedisTemplate.opsForValue().get(dayKey));
        long monthUsed = parseLongOrZero(stringRedisTemplate.opsForValue().get(monthKey));

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
    }

    private TenantQuotaConfig resolveQuotaConfig(TenantContext tenantContext) {
        long minuteQuota = properties.getRateLimit().getDefaultTokenQuotaPerMinute();
        long dayQuota = properties.getRateLimit().getDefaultTokenQuotaPerDay();
        long monthQuota = Math.max(dayQuota, dayQuota * 30);
        if (tenantContext == null) {
            return new TenantQuotaConfig(minuteQuota, dayQuota, monthQuota);
        }
        AiGatewayProperties.TenantQuotaPolicy quotaPolicy = tenantConfigQueryService.findQuotaPolicy(tenantContext.tenantId()).orElse(null);
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

    private String appIdOf(TenantContext tenantContext) {
        return tenantContext == null ? properties.getTenant().getDefaultAppId() : tenantContext.appId();
    }

    private DefaultRedisScript<Long> tokenQuotaLuaScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
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
                """);
        return script;
    }

    private DefaultRedisScript<Long> quotaCompensationLuaScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
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
                """);
        return script;
    }

    private record TenantQuotaConfig(long minuteQuota, long dayQuota, long monthQuota) {
    }
}
