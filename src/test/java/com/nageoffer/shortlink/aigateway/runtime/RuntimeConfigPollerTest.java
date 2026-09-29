package com.nageoffer.shortlink.aigateway.runtime;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.stream.Stream;

/**
 * 轮询端的四条不变量：首次全量、之后只重载变化的域、版本缺失映射为 0 且不循环重载、Redis 挂了不抛给调用方。
 * <p>
 * 其中"首次全量"是刻意的取舍（见 {@link RuntimeConfigPoller} 类注释），
 * 所以断言里允许第一次调 {@code reload} 六次 —— 别把它当 bug 改掉。
 */
class RuntimeConfigPollerTest {

    @Test
    void shouldReloadEveryDomainOnFirstPollThenOnlyChangedOnes() {
        AiGatewayProperties properties = new AiGatewayProperties();
        RuntimeConfigService service = Mockito.mock(RuntimeConfigService.class);
        RuntimeConfigVersions versions = Mockito.mock(RuntimeConfigVersions.class);
        Mockito.when(versions.readAll()).thenReturn(Mono.just(Map.of("cache", 1L)));

        RuntimeConfigPoller poller = poller(properties, service, versions);

        poller.pollOnce().block();
        Mockito.verify(service, Mockito.times(6)).reload(Mockito.any());

        Mockito.clearInvocations(service);
        poller.pollOnce().block();
        Mockito.verify(service, Mockito.never()).reload(Mockito.any());

        Mockito.clearInvocations(service);
        Mockito.when(versions.readAll()).thenReturn(Mono.just(Map.of("cache", 2L)));
        poller.pollOnce().block();

        Mockito.verify(service).reload(RuntimeConfigDomain.CACHE);
        Mockito.verify(service, Mockito.times(1)).reload(Mockito.any());
    }

    @Test
    void shouldNotReloadRepeatedlyWhenVersionsGoMissing() {
        AiGatewayProperties properties = new AiGatewayProperties();
        RuntimeConfigService service = Mockito.mock(RuntimeConfigService.class);
        RuntimeConfigVersions versions = Mockito.mock(RuntimeConfigVersions.class);
        // 首次就带版本，让六个域都记下"已应用版本"
        Mockito.when(versions.readAll()).thenReturn(Mono.just(Map.of(
                "routing", 1L, "rateLimit", 1L, "cache", 1L,
                "safety", 1L, "plugin", 1L, "security", 1L)));

        RuntimeConfigPoller poller = poller(properties, service, versions);
        poller.pollOnce().block();
        Mockito.clearInvocations(service);

        // Redis 被清空：版本缺失映射为 0，只重载一次
        Mockito.when(versions.readAll()).thenReturn(Mono.just(Map.of()));
        poller.pollOnce().block();
        Mockito.verify(service, Mockito.times(6)).reload(Mockito.any());

        Mockito.clearInvocations(service);
        poller.pollOnce().block();
        Mockito.verify(service, Mockito.never()).reload(Mockito.any());
    }

    @Test
    void shouldNotThrowWhenRedisIsUnavailable() {
        AiGatewayProperties properties = new AiGatewayProperties();
        RuntimeConfigService service = Mockito.mock(RuntimeConfigService.class);
        RuntimeConfigVersions versions = Mockito.mock(RuntimeConfigVersions.class);
        Mockito.when(versions.readAll()).thenReturn(Mono.error(new IllegalStateException("redis down")));

        RuntimeConfigPoller poller = poller(properties, service, versions);

        Assertions.assertDoesNotThrow(poller::poll);
        Mockito.verify(service, Mockito.never()).reload(Mockito.any());

        // 错误在 pollOnce 上如实传出，由调度入口决定怎么处理
        Assertions.assertThrows(IllegalStateException.class, () -> poller.pollOnce().block());

        // 失败后不能卡住开关，下一轮仍要能跑
        Mockito.when(versions.readAll()).thenReturn(Mono.just(Map.of("cache", 1L)));
        Assertions.assertDoesNotThrow(() -> poller.pollOnce().block());
    }

    @Test
    void shouldSkipPollingWhenDisabled() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getRuntimeConfig().setEnabled(false);
        RuntimeConfigService service = Mockito.mock(RuntimeConfigService.class);
        RuntimeConfigVersions versions = Mockito.mock(RuntimeConfigVersions.class);

        poller(properties, service, versions).poll();

        Mockito.verify(versions, Mockito.never()).readAll();
        Mockito.verify(service, Mockito.never()).reload(Mockito.any());
    }

    @Test
    void shouldRejectOverlappingPoll() {
        AiGatewayProperties properties = new AiGatewayProperties();
        RuntimeConfigService service = Mockito.mock(RuntimeConfigService.class);
        RuntimeConfigVersions versions = Mockito.mock(RuntimeConfigVersions.class);
        Sinks.One<Map<String, Long>> gate = Sinks.one();
        Mockito.when(versions.readAll()).thenReturn(gate.asMono());

        RuntimeConfigPoller poller = poller(properties, service, versions);

        Mono<Void> first = poller.pollOnce();
        Mono<Void> second = poller.pollOnce();

        Assertions.assertNull(second.block(), "上一轮还没跑完时，本轮的 Mono 应该是空的");

        gate.tryEmitValue(Map.of());
        first.block();

        Mockito.verify(versions, Mockito.times(1)).readAll();
    }

    @Test
    void shouldDriveReloadersThatAreNotBackedByProperties() {
        AiGatewayProperties properties = new AiGatewayProperties();
        RuntimeConfigService service = Mockito.mock(RuntimeConfigService.class);
        RuntimeConfigVersions versions = Mockito.mock(RuntimeConfigVersions.class);
        Mockito.when(versions.readAll()).thenReturn(Mono.just(Map.of("channelHealth", 5L)));

        RuntimeConfigReloader reloader = Mockito.mock(RuntimeConfigReloader.class);
        Mockito.when(reloader.key()).thenReturn("channelHealth");
        Mockito.when(reloader.reload()).thenReturn(Mono.empty());

        RuntimeConfigPoller poller = poller(properties, service, versions, reloader);

        poller.pollOnce().block();
        Mockito.verify(reloader).reload();

        Mockito.clearInvocations(reloader);
        poller.pollOnce().block();
        Mockito.verify(reloader, Mockito.never()).reload();
    }

    @SuppressWarnings("unchecked")
    private RuntimeConfigPoller poller(AiGatewayProperties properties,
                                       RuntimeConfigService service,
                                       RuntimeConfigVersions versions,
                                       RuntimeConfigReloader... reloaders) {
        // reload 的默认行为在 helper 里给掉：每个用例都只想验证"调了几次"，不关心返回值
        Mockito.lenient().when(service.reload(Mockito.any())).thenReturn(Mono.empty());
        ObjectProvider<RuntimeConfigReloader> provider = Mockito.mock(ObjectProvider.class);
        // Stream 只能消费一次，所以每次调用都要新造一个
        Mockito.when(provider.orderedStream()).thenAnswer(ignored -> Stream.of(reloaders));
        return new RuntimeConfigPoller(properties, service, versions, provider);
    }
}
