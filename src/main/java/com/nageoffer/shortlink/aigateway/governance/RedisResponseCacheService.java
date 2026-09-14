package com.nageoffer.shortlink.aigateway.governance;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RedisResponseCacheService {

    private static final String CACHE_PREFIX = "short-link:ai-gateway:cache:";

    private final ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    /**
     * 读取缓存，未命中时返回空 {@link Mono}（而非携带 Optional 的 Mono）。
     * <p>
     * 使用响应式客户端以保证调用不会阻塞 Netty 事件循环。
     */
    public Mono<String> get(String hashKey) {
        return reactiveStringRedisTemplate.opsForValue().get(CACHE_PREFIX + hashKey);
    }

    public Mono<Void> put(String hashKey, String value, Duration ttl) {
        return reactiveStringRedisTemplate.opsForValue().set(CACHE_PREFIX + hashKey, value, ttl).then();
    }

    public Mono<Void> evict(String hashKey) {
        return reactiveStringRedisTemplate.delete(CACHE_PREFIX + hashKey).then();
    }
}
