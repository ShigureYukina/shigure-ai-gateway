package com.nageoffer.shortlink.aigateway.governance;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RedisResponseCacheService {

    private static final String CACHE_PREFIX = "short-link:ai-gateway:cache:";

    private final StringRedisTemplate stringRedisTemplate;

    public Optional<String> get(String hashKey) {
        return Optional.ofNullable(stringRedisTemplate.opsForValue().get(CACHE_PREFIX + hashKey));
    }

    public void put(String hashKey, String value, Duration ttl) {
        stringRedisTemplate.opsForValue().set(CACHE_PREFIX + hashKey, value, ttl);
    }

    public void evict(String hashKey) {
        stringRedisTemplate.delete(CACHE_PREFIX + hashKey);
    }
}
