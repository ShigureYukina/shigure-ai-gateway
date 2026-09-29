package com.nageoffer.shortlink.aigateway.runtime;

import reactor.core.publisher.Mono;

/**
 * 复用运行时配置的跨实例版本号、但数据源不是 {@code AiGatewayProperties} 的刷新器。
 * <p>
 * 存在的意义是让轮询器只认"版本变了"这一个信号，而不必知道被刷新的是什么。
 * 渠道健康（{@code provider_health} 表）就是这样接入的。
 */
public interface RuntimeConfigReloader {

    /**
     * 版本号 hash 里的域名，例如 {@code provider-health}。
     */
    String key();

    /**
     * 按库里的最新数据重建内存快照。实现必须自己吞掉异常（刷新失败不能影响轮询线程）。
     */
    Mono<Void> reload();
}
