package com.nageoffer.shortlink.aigateway.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;

/**
 * 运行时配置的版本号读写。
 * <p>
 * 版本号只回答一个问题："自上次之后，有没有被谁改过？"因此它不需要单调、不需要与 DB 的 version 列一致，
 * 只要求"每次写入都换一个新的值"。Redis 抖动时用时间戳兜底，写入链路不该因为发不出通知而失败。
 * <p>
 * 全程用同步客户端包在弹性线程池上：这些动作都在管理面（低频），不必为它把 {@code StringRedisTemplate}
 * 换成响应式客户端。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeConfigVersions {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 一次 HGETALL 拿到所有域的版本号。
     * <p>
     * Redis 不可用时**原样抛出**（而不是返回空 Map）：调用方需要区分"没有版本记录"（映射为 0，
     * 什么都不用做）与"读不到"（保持现有内存值、不要动）。
     */
    public Mono<Map<String, Long>> readAll() {
        return Mono.fromCallable(() -> {
            Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(RuntimeConfigKeys.VERSION_KEY);
            Map<String, Long> versions = new HashMap<>();
            raw.forEach((key, value) -> {
                Long parsed = parseVersion(value);
                if (parsed != null) {
                    versions.put(String.valueOf(key), parsed);
                }
            });
            return versions;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 生成一个新的版本令牌：优先用 Redis 自增序号，不可用时退化为当前毫秒。
     */
    public Mono<Long> nextToken() {
        return Mono.fromCallable(() -> {
            try {
                Long seq = stringRedisTemplate.opsForValue().increment(RuntimeConfigKeys.SEQ_KEY);
                if (seq != null) {
                    return seq;
                }
            } catch (Exception ex) {
                log.debug("redis increment unavailable for runtime config version, falling back to timestamp: {}", ex.getMessage());
            }
            return System.currentTimeMillis();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 广播某域的版本号。
     * <p>
     * 失败只记 warn 不让写请求失败：配置已经落库了，发不出通知只影响"别的实例多快看到"，
     * 把写请求标成失败反而会让运维重复提交。
     */
    public Mono<Void> publish(String domainKey, long token) {
        return Mono.fromRunnable(() -> {
            try {
                stringRedisTemplate.opsForHash().put(RuntimeConfigKeys.VERSION_KEY, domainKey, String.valueOf(token));
            } catch (Exception ex) {
                log.warn("failed to publish runtime config version domain={}: {}", domainKey, ex.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private static Long parseVersion(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
