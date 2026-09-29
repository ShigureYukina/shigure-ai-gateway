package com.nageoffer.shortlink.aigateway.probe;

import com.nageoffer.shortlink.aigateway.routing.ChannelHealthRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 渠道健康接入跨实例配置同步的适配器。
 * <p>
 * 两个契约值得单独钉住：<b>域名必须与探测服务广播时用的一致</b>（不一致就永远收不到通知，
 * 而且是静默的），以及<b>刷新失败必须自己吞掉</b>（这是轮询线程的调用，一次 DB 抖动
 * 不该让整轮配置同步挂掉）。
 */
class ChannelHealthReloaderTest {

    @Test
    void shouldUseTheSameDomainKeyAsTheProbeService() {
        ChannelHealthReloader reloader = new ChannelHealthReloader(Mockito.mock(ChannelHealthRegistry.class));

        Assertions.assertEquals("provider-health", reloader.key());
    }

    @Test
    void shouldRebuildSnapshotOnReload() {
        ChannelHealthRegistry registry = Mockito.mock(ChannelHealthRegistry.class);
        Mockito.when(registry.refresh()).thenReturn(Mono.empty());

        StepVerifier.create(new ChannelHealthReloader(registry).reload()).verifyComplete();

        Mockito.verify(registry).refresh();
    }

    @Test
    void shouldSwallowDatabaseFailureToKeepThePollingThreadAlive() {
        ChannelHealthRegistry registry = Mockito.mock(ChannelHealthRegistry.class);
        Mockito.when(registry.refresh()).thenReturn(Mono.error(new IllegalStateException("db down")));

        // 快照保留旧值由 ChannelHealthRegistry 负责，这里只要求不让异常冒到轮询线程
        StepVerifier.create(new ChannelHealthReloader(registry).reload()).verifyComplete();
    }
}
