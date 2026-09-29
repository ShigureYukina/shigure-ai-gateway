package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 渠道级调用频率限制（RPM）。
 * <p>
 * 与租户 token 配额是两回事：那个保护的是"这个租户别用超"，
 * 这个保护的是"这个渠道别被我们打爆"——上游按分钟限流时，
 * 网关自己先拦住，比把 429 透传给客户端更体面。
 * <p>
 * 采用分钟定窗计数（INCR + EXPIRE），一次往返、跨实例一致。
 * 未配置 {@code rpm-limit} 的渠道完全不做检查——默认零开销，不引入新的 Redis 依赖。
 */
@Slf4j
@Component
public class ProviderRateLimitService {

    private static final String KEY_PREFIX = "short-link:ai-gateway:provider-rpm:";

    private static final long WINDOW_MILLIS = 60_000L;

    private final ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    private final AiGatewayProperties properties;

    @Autowired
    public ProviderRateLimitService(ReactiveStringRedisTemplate reactiveStringRedisTemplate, AiGatewayProperties properties) {
        this.reactiveStringRedisTemplate = reactiveStringRedisTemplate;
        this.properties = properties;
    }

    /**
     * 不接 Redis 的构造方式：渠道未配置限速时（默认）以及测试使用。
     */
    public ProviderRateLimitService(AiGatewayProperties properties) {
        this(null, properties);
    }

    /**
     * 尝试占用一次调用额度。
     *
     * @return true 放行；false 表示该渠道这一分钟已经打满，调用方应换通道或返回 429
     */
    public Mono<Boolean> tryAcquire(String provider) {
        Integer limit = resolveLimit(provider);
        if (limit == null || limit <= 0 || reactiveStringRedisTemplate == null) {
            return Mono.just(Boolean.TRUE);
        }
        String key = KEY_PREFIX + provider + ":" + (System.currentTimeMillis() / WINDOW_MILLIS);
        // 用 defer 包住：opsForValue() 这类同步取操作也可能直接抛，同步异常同样要走 fail-open
        return Mono.defer(() -> reactiveStringRedisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    if (count != null && count == 1L) {
                        return reactiveStringRedisTemplate.expire(key, Duration.ofMinutes(2)).thenReturn(Boolean.TRUE);
                    }
                    boolean allowed = count == null || count <= limit;
                    if (!allowed) {
                        log.warn("provider rpm limit reached: provider={}, limit={}", provider, limit);
                    }
                    return Mono.just(allowed);
                }))
                // 限速是保护手段，不该成为新的故障点：Redis 异常时放行
                .onErrorResume(ex -> {
                    log.warn("provider rpm check failed, allow request: provider={}, reason={}", provider, ex.getMessage());
                    return Mono.just(Boolean.TRUE);
                });
    }

    public Integer resolveLimit(String provider) {
        AiGatewayProperties.ProviderCredential credential = properties.getUpstream().getProviderCredentials().get(provider);
        return credential == null ? null : credential.getRpmLimit();
    }
}
