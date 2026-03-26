package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;

class RedisTokenQuotaServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncrementWhenActualUsageIsHigherThanReserved() {
        AiGatewayProperties properties = buildProperties();
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        RedisTokenQuotaService service = buildService(redisTemplate, properties);
        QuotaPreCheckContext context = QuotaPreCheckContext.builder()
                .reservedTokens(100L)
                .minuteKey("short-link:ai-gateway:quota:minute:k")
                .dayKey("short-link:ai-gateway:quota:day:2026-01-01:k")
                .monthKey("short-link:ai-gateway:quota:month:2026-01:k")
                .build();

        service.adjustByActualUsage(context, 130L);

        Mockito.verify(valueOperations).increment(eq(context.getMinuteKey()), eq(30L));
        Mockito.verify(valueOperations).increment(eq(context.getDayKey()), eq(30L));
        Mockito.verify(valueOperations).increment(eq(context.getMonthKey()), eq(30L));
        Mockito.verify(redisTemplate, Mockito.never()).execute(any(DefaultRedisScript.class), anyList(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCompensateWhenActualUsageIsLowerThanReserved() {
        AiGatewayProperties properties = buildProperties();
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        RedisTokenQuotaService service = buildService(redisTemplate, properties);
        QuotaPreCheckContext context = QuotaPreCheckContext.builder()
                .reservedTokens(120L)
                .minuteKey("short-link:ai-gateway:quota:minute:k")
                .dayKey("short-link:ai-gateway:quota:day:2026-01-01:k")
                .monthKey("short-link:ai-gateway:quota:month:2026-01:k")
                .build();

        service.adjustByActualUsage(context, 80L);

        Mockito.verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(List.of(context.getMinuteKey(), context.getDayKey(), context.getMonthKey())), eq("40"));
        Mockito.verify(valueOperations, Mockito.never()).increment(anyString(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldIgnoreInvalidAdjustInputs() {
        AiGatewayProperties properties = buildProperties();
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        RedisTokenQuotaService service = buildService(redisTemplate, properties);

        service.adjustByActualUsage(null, 100L);
        service.adjustByActualUsage(QuotaPreCheckContext.builder().reservedTokens(0L).build(), 100L);
        service.adjustByActualUsage(QuotaPreCheckContext.builder().reservedTokens(100L).build(), 0L);

        Mockito.verify(redisTemplate, Mockito.never()).execute(any(DefaultRedisScript.class), anyList(), anyString());
        Mockito.verify(valueOperations, Mockito.never()).increment(anyString(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnCurrentUsageAndParseInvalidRedisValueAsZero() {
        AiGatewayProperties properties = buildProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setDefaultTokenQuotaPerMinute(1000L);
        properties.getRateLimit().setDefaultTokenQuotaPerDay(5000L);

        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.when(valueOperations.get(startsWith("short-link:ai-gateway:quota:minute:"))).thenReturn("abc");
        Mockito.when(valueOperations.get(startsWith("short-link:ai-gateway:quota:day:"))).thenReturn("300");
        Mockito.when(valueOperations.get(startsWith("short-link:ai-gateway:quota:month:"))).thenReturn("1200");

        RedisTokenQuotaService service = buildService(redisTemplate, properties);

        HttpHeaders headers = new HttpHeaders();
        headers.set("userId", "u-1");
        headers.set("X-Forwarded-For", "127.0.0.1");
        headers.set("X-Consumer", "demo");

        Map<String, Object> usage = service.currentUsage(headers, "openai", "gpt-4o-mini");

        Assertions.assertEquals(Boolean.TRUE, usage.get("enabled"));
        Assertions.assertEquals("openai", usage.get("provider"));
        Assertions.assertEquals("gpt-4o-mini", usage.get("model"));
        Assertions.assertEquals(1000L, usage.get("minuteQuota"));
        Assertions.assertEquals(0L, usage.get("minuteUsed"));
        Assertions.assertEquals(1000L, usage.get("minuteRemaining"));
        Assertions.assertEquals(5000L, usage.get("dayQuota"));
        Assertions.assertEquals(300L, usage.get("dayUsed"));
        Assertions.assertEquals(4700L, usage.get("dayRemaining"));
        Assertions.assertEquals(150000L, usage.get("monthQuota"));
        Assertions.assertEquals(1200L, usage.get("monthUsed"));
        Assertions.assertEquals(148800L, usage.get("monthRemaining"));
    }

    @Test
    void shouldRunPreCheckAsPassWhenRateLimitDisabled() {
        AiGatewayProperties properties = buildProperties();
        properties.getRateLimit().setEnabled(false);
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);

        RedisTokenQuotaService service = buildService(redisTemplate, properties);

        AiChatCompletionReqDTO request = new AiChatCompletionReqDTO();
        request.setModel("gpt-4o-mini");
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("user");
        message.setContent("hello");
        request.setMessages(List.of(message));

        QuotaPreCheckContext context = service.preCheck(new TenantContext("tenant-a", "app-a", "key-a"), new HttpHeaders(), "openai", "gpt-4o-mini", request);

        Assertions.assertEquals(0L, context.getReservedTokens());
        Mockito.verifyNoInteractions(redisTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldUseTenantScopedQuotaAndQuotaKey() {
        AiGatewayProperties properties = buildProperties();
        AiGatewayProperties.TenantQuotaPolicy quotaPolicy = new AiGatewayProperties.TenantQuotaPolicy();
        quotaPolicy.setTokenQuotaPerMinute(200L);
        quotaPolicy.setTokenQuotaPerDay(1000L);
        quotaPolicy.setTokenQuotaPerMonth(20000L);
        properties.getTenant().getQuotaPolicies().put("tenant-a", quotaPolicy);

        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        RedisTokenQuotaService service = buildService(redisTemplate, properties);
        AiChatCompletionReqDTO request = request();

        QuotaPreCheckContext context = service.preCheck(new TenantContext("tenant-a", "app-a", "key-a"), new HttpHeaders(), "openai", "gpt-4o-mini", request);

        Assertions.assertEquals(200L, context.getMinuteQuota());
        Assertions.assertEquals(1000L, context.getDayQuota());
        Assertions.assertEquals(20000L, context.getMonthQuota());
        Assertions.assertTrue(context.getQuotaKey().contains("tenantId=tenant-a"));
        Assertions.assertTrue(context.getQuotaKey().contains("appId=app-a"));
        Assertions.assertTrue(context.getQuotaKey().contains("keyId=key-a"));
        Mockito.verify(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), anyString(), eq("200"), eq("1000"), eq("20000"), anyString(), anyString(), anyString());
        Mockito.verify(serviceMetricsRecorder(service)).recordTenantQuotaEvent("tenant-a", "app-a", "openai", "gpt-4o-mini", "reserve", context.getReservedTokens());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectWhenTenantMonthQuotaExceeded() {
        AiGatewayProperties properties = buildProperties();
        AiGatewayProperties.TenantQuotaPolicy quotaPolicy = new AiGatewayProperties.TenantQuotaPolicy();
        quotaPolicy.setTokenQuotaPerMinute(1000L);
        quotaPolicy.setTokenQuotaPerDay(10000L);
        quotaPolicy.setTokenQuotaPerMonth(10L);
        properties.getTenant().getQuotaPolicies().put("tenant-a", quotaPolicy);

        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        RedisTokenQuotaService service = buildService(redisTemplate, properties);

        Assertions.assertThrows(
                com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException.class,
                () -> service.preCheck(new TenantContext("tenant-a", "app-a", "key-a"), new HttpHeaders(), "openai", "gpt-4o-mini", request())
        );
        Mockito.verify(serviceMetricsRecorder(service)).recordTenantQuotaEvent("tenant-a", "app-a", "openai", "gpt-4o-mini", "reject", 65L);
    }

    private AiChatCompletionReqDTO request() {
        AiChatCompletionReqDTO request = new AiChatCompletionReqDTO();
        request.setModel("gpt-4o-mini");
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("user");
        message.setContent("hello");
        request.setMessages(List.of(message));
        return request;
    }

    private RedisTokenQuotaService buildService(StringRedisTemplate redisTemplate, AiGatewayProperties properties) {
        TokenEstimator tokenEstimator = new TokenEstimator(properties);
        QuotaKeyGenerator quotaKeyGenerator = new QuotaKeyGenerator(properties);
        return new RedisTokenQuotaService(redisTemplate, tokenEstimator, quotaKeyGenerator, properties, Mockito.mock(AiGatewayMetricsRecorder.class));
    }

    private AiGatewayMetricsRecorder serviceMetricsRecorder(RedisTokenQuotaService service) {
        try {
            java.lang.reflect.Field field = RedisTokenQuotaService.class.getDeclaredField("metricsRecorder");
            field.setAccessible(true);
            return (AiGatewayMetricsRecorder) field.get(service);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private AiGatewayProperties buildProperties() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setDefaultTokenQuotaPerMinute(1000L);
        properties.getRateLimit().setDefaultTokenQuotaPerDay(10000L);
        properties.getRateLimit().setMinTokenReserve(64L);
        return properties;
    }
}
