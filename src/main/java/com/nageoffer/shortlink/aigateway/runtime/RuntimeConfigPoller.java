package com.nageoffer.shortlink.aigateway.runtime;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨实例配置同步的轮询端。
 * <p>
 * 每轮只做一次 HGETALL：拿各域的版本号与"本实例已应用过的版本"比对，只重载变化过的域。
 * 之所以不用 Redis Pub/Sub：轮询没有订阅者生命周期与消息丢失的问题，间隔 5 秒对管理面配置完全够用，
 * 也不必为一个低频能力引入新的消息机制。代价是各实例最多晚一个轮询周期看到变更。
 * <p>
 * 三条容错约定：
 * <ul>
 *   <li>Redis 读失败 → 保持现有内存值，不动（fail-static），日志按 30 秒节流；</li>
 *   <li>Redis 被清空 → 版本缺失映射为 0，与本地已应用值比对后最多重载一次，不会每轮都重载；</li>
 *   <li>本实例已有轮询在跑 → 直接跳过（{@code AtomicBoolean}，与同步任务同一套路，只是防止单实例重入）。</li>
 * </ul>
 * 第一次轮询会把六个域全部重载一遍（本地还没有"已应用版本"记录）——这是刻意的：
 * 与其在启动期抢时间窗口做播种，不如多读 6 行，换"不会漏掉启动瞬间别人写的那次变更"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeConfigPoller {

    private static final long ERROR_LOG_INTERVAL_MILLIS = 30_000L;

    private final AiGatewayProperties properties;

    private final RuntimeConfigService runtimeConfigService;

    private final RuntimeConfigVersions runtimeConfigVersions;

    /**
     * 复用同一套版本号、但数据源不是 properties 的刷新器（渠道健康等）。
     */
    private final ObjectProvider<RuntimeConfigReloader> reloaders;

    private final Map<String, Long> appliedVersions = new ConcurrentHashMap<>();

    private final AtomicBoolean polling = new AtomicBoolean(false);

    private final AtomicLong lastErrorLoggedAt = new AtomicLong(0L);

    @Scheduled(initialDelayString = "${short-link.ai-gateway.runtime-config.poll-initial-delay:PT10S}",
            fixedDelayString = "${short-link.ai-gateway.runtime-config.poll-interval:PT5S}")
    public void poll() {
        if (!properties.getRuntimeConfig().isEnabled()) {
            return;
        }
        pollOnce().subscribe(null, this::logThrottled);
    }

    /**
     * 轮询一次，供定时任务与测试共用。
     */
    public Mono<Void> pollOnce() {
        if (!polling.compareAndSet(false, true)) {
            return Mono.empty();
        }
        return runtimeConfigVersions.readAll()
                .flatMap(this::applyChanges)
                .doFinally(signal -> polling.set(false));
    }

    private Mono<Void> applyChanges(Map<String, Long> versions) {
        List<Mono<Void>> tasks = new ArrayList<>();
        for (RuntimeConfigDomain domain : RuntimeConfigDomain.values()) {
            long token = versions.getOrDefault(domain.key(), 0L);
            if (shouldReload(domain.key(), token)) {
                tasks.add(runtimeConfigService.reload(domain)
                        .doOnSuccess(ignored -> appliedVersions.put(domain.key(), token)));
            }
        }
        reloaders.orderedStream().forEach(reloader -> {
            long token = versions.getOrDefault(reloader.key(), 0L);
            if (shouldReload(reloader.key(), token)) {
                tasks.add(reloader.reload()
                        .doOnSuccess(ignored -> appliedVersions.put(reloader.key(), token)));
            }
        });
        if (tasks.isEmpty()) {
            return Mono.empty();
        }
        return Flux.merge(tasks).then();
    }

    private boolean shouldReload(String key, long token) {
        Long applied = appliedVersions.get(key);
        return applied == null || applied != token;
    }

    private void logThrottled(Throwable ex) {
        long now = System.currentTimeMillis();
        long last = lastErrorLoggedAt.get();
        if (now - last >= ERROR_LOG_INTERVAL_MILLIS && lastErrorLoggedAt.compareAndSet(last, now)) {
            log.warn("runtime config poll failed, keeping current in-memory values: {}", ex.getMessage());
        }
    }
}
