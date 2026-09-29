package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;

import java.time.Duration;

class ProviderRateLimitServiceTest {

    @Test
    void shouldAllowEverythingWhenNoLimitConfigured() {
        AiGatewayProperties properties = new AiGatewayProperties();
        // 不接 Redis：默认不配 rpm-limit 时，限速路径连一次 Redis 都不该碰
        ProviderRateLimitService service = new ProviderRateLimitService(properties);

        Assertions.assertEquals(Boolean.TRUE, service.tryAcquire("openai").block());
        Assertions.assertNull(service.resolveLimit("openai"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectRequestBeyondProviderRpmLimit() {
        AiGatewayProperties properties = propertiesWithLimit("openai", 2);
        ReactiveStringRedisTemplate redisTemplate = Mockito.mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOperations = Mockito.mock(ReactiveValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.when(valueOperations.increment(Mockito.anyString())).thenReturn(Mono.just(1L), Mono.just(2L), Mono.just(3L));
        Mockito.when(redisTemplate.expire(Mockito.anyString(), Mockito.any(Duration.class))).thenReturn(Mono.just(true));

        ProviderRateLimitService service = new ProviderRateLimitService(redisTemplate, properties);

        Assertions.assertEquals(Boolean.TRUE, service.tryAcquire("openai").block());
        Assertions.assertEquals(Boolean.TRUE, service.tryAcquire("openai").block());
        // 超过上限的那次要被拦住，由上层换通道或返回 429
        Assertions.assertEquals(Boolean.FALSE, service.tryAcquire("openai").block());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldFailOpenWhenRedisIsDown() {
        AiGatewayProperties properties = propertiesWithLimit("openai", 1);
        ReactiveStringRedisTemplate redisTemplate = Mockito.mock(ReactiveStringRedisTemplate.class);
        Mockito.when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        ProviderRateLimitService service = new ProviderRateLimitService(redisTemplate, properties);

        // 限速是保护手段，不能反过来成为新的故障点
        Assertions.assertEquals(Boolean.TRUE, service.tryAcquire("openai").block());
    }

    private AiGatewayProperties propertiesWithLimit(String provider, int limit) {
        AiGatewayProperties properties = new AiGatewayProperties();
        AiGatewayProperties.ProviderCredential credential = new AiGatewayProperties.ProviderCredential();
        credential.setApiKey("sk-test");
        credential.setRpmLimit(limit);
        properties.getUpstream().getProviderCredentials().put(provider, credential);
        return properties;
    }
}
