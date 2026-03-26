package com.nageoffer.shortlink.aigateway.governance;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;

class RedisResponseCacheServiceTest {

    @Test
    void shouldGetPutAndEvictWithPrefix() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOps);

        RedisResponseCacheService service = new RedisResponseCacheService(redisTemplate);
        Duration ttl = Duration.ofSeconds(30);

        service.put("k1", "v1", ttl);
        Mockito.verify(valueOps).set(eq("short-link:ai-gateway:cache:k1"), eq("v1"), eq(ttl));

        Mockito.when(valueOps.get("short-link:ai-gateway:cache:k1")).thenReturn("cached");
        Optional<String> cached = service.get("k1");
        Assertions.assertTrue(cached.isPresent());
        Assertions.assertEquals("cached", cached.get());

        Mockito.when(valueOps.get("short-link:ai-gateway:cache:k2")).thenReturn(null);
        Assertions.assertTrue(service.get("k2").isEmpty());

        service.evict("k1");
        Mockito.verify(redisTemplate).delete("short-link:ai-gateway:cache:k1");
    }
}
