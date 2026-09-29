package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderHealthEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderHealthRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 渠道健康快照。
 * <p>
 * 三条最要紧的：<b>fail-open</b>（快照为空或没这行 = 可用，探测体系坏掉不能把所有渠道一起关掉）、
 * <b>DB 报错保留旧快照</b>（一次查询抖动不该让路由瞬间以为"全部健康"）、
 * <b>yml 静态禁用优先</b>（跟着发布走，快照里是 UP 也不放行）。
 */
class ChannelHealthRegistryTest {

    private final AiGatewayProperties properties = new AiGatewayProperties();

    private ProviderHealthRepository repository;

    @Test
    void shouldTreatUnknownProviderAsAllowedWhenSnapshotIsEmpty() {
        ChannelHealthRegistry registry = registry(List.of());
        registry.refresh().block();

        // 表里一行都没有（还没探过）= 不该拦。这是"探测误判把网关打死"的第一道防线
        Assertions.assertTrue(registry.allows("openai"));
        Assertions.assertTrue(registry.disabledProviders().isEmpty());
        Assertions.assertNull(registry.find("openai"));
        Assertions.assertEquals("database", registry.source());
    }

    @Test
    void shouldTreatAllProvidersAsAllowedWhenNoRepositoryIsRegistered() {
        ChannelHealthRegistry registry = registry(properties, null);

        Assertions.assertTrue(registry.allows("openai"));
        Assertions.assertEquals("memory", registry.source());
    }

    @Test
    void shouldLoadSnapshotFromDatabase() {
        ChannelHealthRegistry registry = registry(List.of(
                row("claude", ProviderHealthEntity.STATUS_DOWN, false, 3, 0),
                row("openai", ProviderHealthEntity.STATUS_UP, true, 0, 5),
                row("qwen", ProviderHealthEntity.STATUS_UP, false, 0, 7)));

        registry.refresh().block();

        Assertions.assertFalse(registry.allows("claude"), "DOWN 不可路由");
        Assertions.assertFalse(registry.allows("openai"), "人工禁用不可路由");
        Assertions.assertTrue(registry.allows("qwen"));
        Assertions.assertTrue(registry.allows("gemini"), "没这行 = 可用");
        Assertions.assertEquals("database", registry.source());
        Assertions.assertEquals(7, registry.find("qwen").consecutiveSuccesses());
    }

    @Test
    void shouldReplaceSnapshotWholesaleInsteadOfMerging() {
        ChannelHealthRegistry registry = registry(List.of(
                row("openai", ProviderHealthEntity.STATUS_UP, true, 0, 5)));
        registry.refresh().block();
        Assertions.assertFalse(registry.allows("openai"));

        // 第二轮只返回 claude：上一轮 openai 的人工禁用结论必须被丢掉，
        // 否则一个渠道被恢复（行被删或状态改回）后，旧结论会永久留在快照里
        Mockito.when(repository.findAll()).thenReturn(Flux.just(
                row("claude", ProviderHealthEntity.STATUS_DOWN, false, 3, 0)));
        registry.refresh().block();

        Assertions.assertTrue(registry.allows("openai"));
        Assertions.assertFalse(registry.allows("claude"));
    }

    @Test
    void shouldKeepPreviousSnapshotWhenDatabaseFails() {
        ChannelHealthRegistry registry = registry(List.of(
                row("claude", ProviderHealthEntity.STATUS_DOWN, false, 3, 0)));
        registry.refresh().block();
        Assertions.assertFalse(registry.allows("claude"));

        Mockito.when(repository.findAll()).thenReturn(Flux.error(new IllegalStateException("db down")));

        Assertions.assertThrows(IllegalStateException.class, () -> registry.refresh().block());
        Assertions.assertFalse(registry.allows("claude"), "旧快照必须保留");
        Assertions.assertEquals("database", registry.source(), "来源不能退回 memory");
    }

    @Test
    void shouldLetYmlStaticDisableWinOverTheSnapshot() {
        properties.getProbe().getChannelEnabled().put("claude", false);
        ChannelHealthRegistry registry = registry(List.of(
                row("claude", ProviderHealthEntity.STATUS_UP, false, 0, 9)));

        registry.refresh().block();

        // 快照说 UP，但 yml 静态禁用是发布配置，任何时候都生效
        Assertions.assertFalse(registry.allows("claude"));
        Assertions.assertTrue(registry.disabledProviders().contains("claude"));
    }

    @Test
    void shouldReportBothKindsOfDisabledProviders() {
        properties.getProbe().getChannelEnabled().put("gemini", false);
        ChannelHealthRegistry registry = registry(List.of(
                row("claude", ProviderHealthEntity.STATUS_DOWN, false, 3, 0),
                row("openai", ProviderHealthEntity.STATUS_UP, true, 0, 5),
                row("qwen", ProviderHealthEntity.STATUS_UP, false, 0, 5)));

        registry.refresh().block();

        Assertions.assertEquals(Set.of("gemini", "claude", "openai"), registry.disabledProviders());
    }

    @Test
    void shouldExposeCooldownSoProbeCanSkipCoolingChannels() {
        ProviderHealthEntity cooling = row("claude", ProviderHealthEntity.STATUS_DOWN, false, 3, 0);
        cooling.setDisabledUntil(LocalDateTime.now().plusMinutes(5));

        Assertions.assertFalse(ChannelHealthRegistry.ChannelHealth.from(cooling).cooldownElapsed(),
                "冷却未过时探测应跳过该渠道");
        Assertions.assertFalse(ChannelHealthRegistry.ChannelHealth.from(cooling).usable());

        cooling.setDisabledUntil(LocalDateTime.now().minusSeconds(1));
        Assertions.assertTrue(ChannelHealthRegistry.ChannelHealth.from(cooling).cooldownElapsed());
        Assertions.assertNull(ChannelHealthRegistry.ChannelHealth.from(
                row("x", ProviderHealthEntity.STATUS_DOWN, false, 1, 0)).disabledUntil());
    }

    private ChannelHealthRegistry registry(List<ProviderHealthEntity> rows) {
        repository = Mockito.mock(ProviderHealthRepository.class);
        Mockito.when(repository.findAll()).thenReturn(Flux.fromIterable(rows));
        return registry(properties, repository);
    }

    private static ChannelHealthRegistry registry(AiGatewayProperties properties,
                                                  ProviderHealthRepository repository) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProviderHealthRepository> provider = Mockito.mock(ObjectProvider.class);
        Mockito.lenient().when(provider.getIfAvailable()).thenReturn(repository);
        return new ChannelHealthRegistry(properties, provider);
    }

    private static ProviderHealthEntity row(String provider, String status, boolean manualDisabled,
                                            int failures, int successes) {
        ProviderHealthEntity entity = new ProviderHealthEntity();
        entity.setProvider(provider);
        entity.setStatus(status);
        entity.setManualDisabled(manualDisabled);
        entity.setConsecutiveFailures(failures);
        entity.setConsecutiveSuccesses(successes);
        entity.setLastCheckedAt(LocalDateTime.now());
        return entity;
    }
}
