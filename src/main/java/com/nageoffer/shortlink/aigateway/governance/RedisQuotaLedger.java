package com.nageoffer.shortlink.aigateway.governance;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RedisQuotaLedger {

    private static final String QUOTA_PREFIX = "short-link:ai-gateway:quota:";

    private final StringRedisTemplate stringRedisTemplate;

    public Long increment(String quotaKey, long delta, Duration ttl) {
        String key = QUOTA_PREFIX + quotaKey;
        Long value = stringRedisTemplate.opsForValue().increment(key, delta);
        if (ttl != null) {
            stringRedisTemplate.expire(key, ttl);
        }
        return value;
    }

    public Long getCurrent(String quotaKey) {
        String value = stringRedisTemplate.opsForValue().get(QUOTA_PREFIX + quotaKey);
        return value == null ? 0L : Long.parseLong(value);
    }
}
