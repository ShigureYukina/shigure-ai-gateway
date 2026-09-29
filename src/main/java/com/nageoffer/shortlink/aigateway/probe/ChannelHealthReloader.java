package com.nageoffer.shortlink.aigateway.probe;

import com.nageoffer.shortlink.aigateway.routing.ChannelHealthRegistry;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigReloader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 把渠道健康接进运行时配置的跨实例通知链路。
 * <p>
 * {@code provider_health} 的数据源不是 {@code AiGatewayProperties}，进不了
 * {@code RuntimeConfigDomain} 枚举，所以走 {@link RuntimeConfigReloader} 这条旁路：
 * 谁写库谁负责 bump 这个域的版本号，其余实例的 {@code RuntimeConfigPoller} 读到版本变化后
 * 回调 {@link #reload()}，内存快照跟着更新。
 * <p>
 * 一个类只做这一件事，是为了让"版本号域名"这个字符串只有一个定义点 ——
 * 写方（{@code ChannelProbeService}）与读方（轮询器）引用同一个常量，
 * 不会出现"写的是 provider-health、读的是 channel-health，于是永远收不到通知"这种静默失效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelHealthReloader implements RuntimeConfigReloader {

    /**
     * 版本号 hash 里的域名。
     */
    public static final String KEY = "provider-health";

    private final ChannelHealthRegistry channelHealthRegistry;

    @Override
    public String key() {
        return KEY;
    }

    /**
     * 重建内存快照。
     * <p>
     * 按接口约定吞掉异常：这是轮询线程的调用，一次 DB 抖动不该让整轮配置同步失败，
     * 而且 {@link ChannelHealthRegistry} 刷新失败时会保留旧快照，正是我们要的降级行为。
     */
    @Override
    public Mono<Void> reload() {
        return channelHealthRegistry.refresh()
                .onErrorResume(ex -> {
                    log.warn("failed to reload channel health, keeping current snapshot: {}", ex.getMessage());
                    return Mono.empty();
                });
    }
}
