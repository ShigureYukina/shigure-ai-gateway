package com.nageoffer.shortlink.aigateway.governance;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.eq;

class RedisResponseCacheServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldGetPutAndEvictWithPrefix() {
        ReactiveStringRedisTemplate redisTemplate = Mockito.mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = Mockito.mock(ReactiveValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOps);

        RedisResponseCacheService service = new RedisResponseCacheService(redisTemplate);
        Duration ttl = Duration.ofSeconds(30);

        Mockito.when(valueOps.set(eq("short-link:ai-gateway:cache:k1"), eq("v1"), eq(ttl))).thenReturn(Mono.just(true));
        StepVerifier.create(service.put("k1", "v1", ttl)).verifyComplete();
        Mockito.verify(valueOps).set(eq("short-link:ai-gateway:cache:k1"), eq("v1"), eq(ttl));

        Mockito.when(valueOps.get("short-link:ai-gateway:cache:k1")).thenReturn(Mono.just("cached"));
        StepVerifier.create(service.get("k1")).expectNext("cached").verifyComplete();

        Mockito.when(valueOps.get("short-link:ai-gateway:cache:k2")).thenReturn(Mono.empty());
        StepVerifier.create(service.get("k2")).verifyComplete();

        Mockito.when(redisTemplate.delete("short-link:ai-gateway:cache:k1")).thenReturn(Mono.just(1L));
        StepVerifier.create(service.evict("k1")).verifyComplete();
        Mockito.verify(redisTemplate).delete("short-link:ai-gateway:cache:k1");
    }
}
