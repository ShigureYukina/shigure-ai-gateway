package com.nageoffer.shortlink.aigateway.probe;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderHealthRepository;
import com.nageoffer.shortlink.aigateway.routing.ChannelHealthRegistry;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigVersions;
import com.nageoffer.shortlink.aigateway.sync.UpstreamModelProbe;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.reactivestreams.Subscription;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 主动探测的状态机。
 * <p>
 * 最要紧的三组：
 * <ul>
 *   <li><b>不计数的失败</b>：限流与未配凭证必须不改变任何计数 —— 把它们算成失败会把一个
 *       完好的渠道关掉，还要多等一个冷却周期才能回来；</li>
 *   <li><b>状态迁移只在 SQL 真改了行时才认</b>：条件更新返回 0 行说明别的实例已经先动手了，
 *       此时再广播一次版本号只会让所有实例白刷一遍快照；</li>
 *   <li><b>三层幂等各自可降级</b>：锁被占 → 整轮跳过；Redis 挂了 → 照探（只是不再跨实例去重）；
 *       本实例重入 → 直接返回。</li>
 * </ul>
 */
class ChannelProbeServiceTest {

    private static final String PROVIDER = "openai";

    private AiGatewayProperties properties;

    private UpstreamModelProbe upstreamModelProbe;

    private ChannelHealthRegistry channelHealthRegistry;

    private ProviderHealthRepository repository;

    private RuntimeConfigVersions runtimeConfigVersions;

    private StringRedisTemplate redis;

    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        // gemini 刻意只出现在优先级里、不给 baseUrl：它不该被探测（拼不出请求地址）
        properties = new AiGatewayProperties();
        properties.getRouting().setProviderPriority(List.of("gemini", "openai"));
        properties.getUpstream().setProviderBaseUrl(new LinkedHashMap<>(Map.of(PROVIDER, "http://127.0.0.1:11434/v1")));
        properties.getUpstream().setDefaultProvider(PROVIDER);

        upstreamModelProbe = Mockito.mock(UpstreamModelProbe.class);
        channelHealthRegistry = Mockito.mock(ChannelHealthRegistry.class);
        repository = Mockito.mock(ProviderHealthRepository.class);
        runtimeConfigVersions = Mockito.mock(RuntimeConfigVersions.class);
        redis = Mockito.mock(StringRedisTemplate.class);
        valueOperations = Mockito.mock(ValueOperations.class);

        Mockito.lenient().when(redis.opsForValue()).thenReturn(valueOperations);
        Mockito.lenient().when(valueOperations.setIfAbsent(Mockito.anyString(), Mockito.anyString(), Mockito.any(Duration.class)))
                .thenReturn(true);
        Mockito.lenient().when(valueOperations.get(Mockito.anyString())).thenReturn(null);
        Mockito.lenient().when(runtimeConfigVersions.nextToken()).thenReturn(Mono.just(7L));
        Mockito.lenient().when(runtimeConfigVersions.publish(Mockito.anyString(), Mockito.anyLong()))
                .thenReturn(Mono.empty());
        Mockito.lenient().when(channelHealthRegistry.refresh()).thenReturn(Mono.empty());
        Mockito.lenient().when(repository.ensureRow(Mockito.anyString())).thenReturn(Mono.just(1));
        Mockito.lenient().when(repository.recordSuccess(Mockito.anyString(), Mockito.anyLong())).thenReturn(Mono.just(1));
        Mockito.lenient().when(repository.recordFailure(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
                .thenReturn(Mono.just(1));
        // 默认"条件更新没改到行"：单项测试要迁移时自己覆盖成 1
        Mockito.lenient().when(repository.markDownIfThresholdReached(Mockito.anyString(), Mockito.anyInt(), Mockito.any()))
                .thenReturn(Mono.just(0));
        Mockito.lenient().when(repository.markUpIfRecovered(Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(Mono.just(0));
        Mockito.lenient().when(repository.markManuallyDisabled(Mockito.anyString())).thenReturn(Mono.just(1));
        Mockito.lenient().when(repository.markManuallyEnabled(Mockito.anyString())).thenReturn(Mono.just(1));
    }

    @Test
    void shouldProbeConfiguredChannelsAndRecordSuccess() {
        probeReturns(ProbeOutcome.success(PROVIDER, List.of("gpt-4o"), 12L));

        StepVerifier.create(service().probeAll())
                .assertNext(summary -> {
                    Assertions.assertEquals(1, summary.probed(), "只有 openai 配了 baseUrl");
                    Assertions.assertEquals(1, summary.healthy());
                    Assertions.assertEquals(0, summary.unhealthy());
                    Assertions.assertTrue(summary.disabled().isEmpty());
                    Assertions.assertTrue(summary.recovered().isEmpty());
                    Assertions.assertFalse(summary.skippedByLock());
                    Assertions.assertNotNull(summary.finishedAt());
                })
                .verifyComplete();

        Mockito.verify(repository).ensureRow(PROVIDER);
        Mockito.verify(repository).recordSuccess(PROVIDER, 12L);
        Mockito.verify(repository, Mockito.never()).recordFailure(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());
        Mockito.verify(repository, Mockito.never())
                .markDownIfThresholdReached(Mockito.anyString(), Mockito.anyInt(), Mockito.any());
        Mockito.verify(runtimeConfigVersions, Mockito.never()).publish(Mockito.anyString(), Mockito.anyLong());
        // 本轮自己没改状态也要刷快照：别的实例可能刚刚改过
        Mockito.verify(channelHealthRegistry).refresh();
    }

    @Test
    void shouldNotProbeChannelWithoutBaseUrl() {
        probeReturns(ProbeOutcome.success(PROVIDER, List.of("gpt-4o"), 1L));

        StepVerifier.create(service().probeAll()).expectNextCount(1).verifyComplete();

        // gemini 在优先级列表里但没有 baseUrl，buildChatUri 必然失败，探它是白打一次上游
        Mockito.verify(upstreamModelProbe, Mockito.never()).probeWithDiagnosis("gemini");
    }

    @Test
    void shouldDeriveLockTtlFromProbeInterval() {
        properties.getProbe().setInterval(Duration.ofMinutes(5));
        probeReturns(ProbeOutcome.success(PROVIDER, List.of("gpt-4o"), 1L));

        StepVerifier.create(service().probeAll()).expectNextCount(1).verifyComplete();

        // 0.9 × 周期：下一轮开始时锁一定已过期，不必依赖"谁来解锁"
        Mockito.verify(valueOperations)
                .setIfAbsent(Mockito.eq(ChannelProbeService.LOCK_KEY), Mockito.anyString(),
                        Mockito.eq(Duration.ofSeconds(270)));
    }

    @Test
    void shouldDisableChannelAndNotifyOtherInstancesWhenThresholdReached() {
        probeReturns(ProbeOutcome.failure(PROVIDER, ProbeErrorType.CONNECT_FAILED, "connection refused", 30L));
        Mockito.when(repository.markDownIfThresholdReached(Mockito.eq(PROVIDER), Mockito.anyInt(), Mockito.any()))
                .thenReturn(Mono.just(1));

        StepVerifier.create(service().probeAll())
                .assertNext(summary -> {
                    Assertions.assertEquals(1, summary.unhealthy());
                    Assertions.assertEquals(List.of(PROVIDER), summary.disabled());
                })
                .verifyComplete();

        Mockito.verify(repository).recordFailure(PROVIDER, "CONNECT_FAILED", 30L);
        ArgumentCaptor<Integer> threshold = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<LocalDateTime> disabledUntil = ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(repository).markDownIfThresholdReached(Mockito.eq(PROVIDER), threshold.capture(), disabledUntil.capture());
        Assertions.assertEquals(properties.getProbe().getFailureThreshold(), threshold.getValue());
        Assertions.assertTrue(disabledUntil.getValue().isAfter(LocalDateTime.now().plusMinutes(4)),
                "禁用时写入的冷却到期时间应约等于 now + down-cooldown");
        Mockito.verify(runtimeConfigVersions).publish(ChannelHealthReloader.KEY, 7L);
    }

    @Test
    void shouldNotBroadcastWhenAnotherInstanceAlreadyMovedTheChannel() {
        probeReturns(ProbeOutcome.failure(PROVIDER, ProbeErrorType.UPSTREAM_5XX, "500", 30L));
        // 条件更新返回 0：状态不是这一轮改的（别的实例先动手，或行被人工禁用了）
        Mockito.when(repository.markDownIfThresholdReached(Mockito.anyString(), Mockito.anyInt(), Mockito.any()))
                .thenReturn(Mono.just(0));

        StepVerifier.create(service().probeAll())
                .assertNext(summary -> Assertions.assertTrue(summary.disabled().isEmpty()))
                .verifyComplete();

        Mockito.verify(runtimeConfigVersions, Mockito.never()).publish(Mockito.anyString(), Mockito.anyLong());
    }

    @Test
    void shouldNotCountRateLimitAsFailure() {
        probeReturns(ProbeOutcome.failure(PROVIDER, ProbeErrorType.RATE_LIMITED, "429", 30L, 429));

        StepVerifier.create(service().probeAll())
                .assertNext(summary -> {
                    Assertions.assertEquals(1, summary.probed());
                    Assertions.assertEquals(0, summary.unhealthy());
                    Assertions.assertEquals(1, summary.inconclusive());
                })
                .verifyComplete();

        // 限流说明渠道是活的，只是此刻排队：既不加失败计数，也不清零（它不构成健康的证据）
        Mockito.verify(repository, Mockito.never()).recordFailure(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());
        Mockito.verify(repository, Mockito.never()).recordSuccess(Mockito.anyString(), Mockito.anyLong());
        Mockito.verify(repository, Mockito.never())
                .markDownIfThresholdReached(Mockito.anyString(), Mockito.anyInt(), Mockito.any());
    }

    @Test
    void shouldNotCountMissingCredentialAsFailure() {
        probeReturns(ProbeOutcome.failure(PROVIDER, ProbeErrorType.NO_CREDENTIAL, "未配置凭证", 0L));

        StepVerifier.create(service().probeAll())
                .assertNext(summary -> {
                    Assertions.assertEquals(1, summary.inconclusive());
                    Assertions.assertEquals(0, summary.unhealthy());
                })
                .verifyComplete();

        Mockito.verify(repository, Mockito.never()).recordFailure(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());
    }

    @Test
    void shouldSkipChannelsThatAreCoolingDown() {
        healthDownCooldownIn(Duration.ofMinutes(5));

        StepVerifier.create(service().probeAll())
                .assertNext(summary -> {
                    Assertions.assertEquals(0, summary.probed());
                    Assertions.assertEquals(List.of(PROVIDER + ": 冷却中"), summary.skipped());
                })
                .verifyComplete();

        // 冷却期内对着已判定宕机的上游持续打请求没有意义
        Mockito.verify(upstreamModelProbe, Mockito.never()).probeWithDiagnosis(Mockito.anyString());
    }

    @Test
    void shouldProbeChannelsWhoseCooldownHasElapsed() {
        healthDownCooldownIn(Duration.ofMinutes(-1));
        probeReturns(ProbeOutcome.success(PROVIDER, List.of("gpt-4o"), 5L));

        StepVerifier.create(service().probeAll())
                .assertNext(summary -> Assertions.assertEquals(1, summary.probed()))
                .verifyComplete();
    }

    @Test
    void shouldSkipManuallyDisabledChannels() {
        Mockito.when(channelHealthRegistry.find(PROVIDER)).thenReturn(
                new ChannelHealthRegistry.ChannelHealth(PROVIDER, false, true, 0, 0, null,
                        LocalDateTime.now(), null, null));

        StepVerifier.create(service().probeAll())
                .assertNext(summary -> {
                    Assertions.assertEquals(0, summary.probed());
                    Assertions.assertEquals(List.of(PROVIDER + ": 已手动禁用"), summary.skipped());
                })
                .verifyComplete();

        Mockito.verify(upstreamModelProbe, Mockito.never()).probeWithDiagnosis(Mockito.anyString());
    }

    @Test
    void shouldRecoverChannelAndNotifyOtherInstances() {
        probeReturns(ProbeOutcome.success(PROVIDER, List.of("gpt-4o"), 8L));
        Mockito.when(repository.markUpIfRecovered(Mockito.eq(PROVIDER), Mockito.anyInt())).thenReturn(Mono.just(1));

        StepVerifier.create(service().probeAll())
                .assertNext(summary -> Assertions.assertEquals(List.of(PROVIDER), summary.recovered()))
                .verifyComplete();

        Mockito.verify(repository).markUpIfRecovered(PROVIDER, properties.getProbe().getRecoveryThreshold());
        Mockito.verify(runtimeConfigVersions).publish(ChannelHealthReloader.KEY, 7L);
    }

    @Test
    void shouldNotBroadcastWhenRecoveryThresholdIsNotReachedYet() {
        probeReturns(ProbeOutcome.success(PROVIDER, List.of("gpt-4o"), 8L));
        Mockito.when(repository.markUpIfRecovered(Mockito.anyString(), Mockito.anyInt())).thenReturn(Mono.just(0));

        StepVerifier.create(service().probeAll())
                .assertNext(summary -> Assertions.assertTrue(summary.recovered().isEmpty()))
                .verifyComplete();

        Mockito.verify(runtimeConfigVersions, Mockito.never()).publish(Mockito.anyString(), Mockito.anyLong());
    }

    @Test
    void shouldSkipWholeRoundWhenAnotherInstanceHoldsTheLock() {
        Mockito.when(valueOperations.setIfAbsent(Mockito.anyString(), Mockito.anyString(), Mockito.any(Duration.class)))
                .thenReturn(false);

        StepVerifier.create(service().probeAll())
                .assertNext(summary -> Assertions.assertTrue(summary.skippedByLock()))
                .verifyComplete();

        Mockito.verify(upstreamModelProbe, Mockito.never()).probeWithDiagnosis(Mockito.anyString());
        // 锁不是自己拿到的，就不能去释放别人的
        Mockito.verify(valueOperations, Mockito.never()).get(Mockito.anyString());
    }

    @Test
    void shouldProbeAnywayWhenRedisIsUnavailable() {
        Mockito.when(valueOperations.setIfAbsent(Mockito.anyString(), Mockito.anyString(), Mockito.any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));
        probeReturns(ProbeOutcome.success(PROVIDER, List.of("gpt-4o"), 3L));
        ChannelProbeService service = service();

        StepVerifier.create(service.probeAll())
                .assertNext(summary -> Assertions.assertEquals(1, summary.probed()))
                .verifyComplete();

        // 拿不到锁就完全不探测，会让一次 Redis 抖动变成"渠道永远不会被禁用"
        Assertions.assertTrue(service.redisDegraded());
    }

    @Test
    void shouldProbeAnywayWhenForced() {
        probeReturns(ProbeOutcome.success(PROVIDER, List.of("gpt-4o"), 3L));

        StepVerifier.create(service().probeAll(true))
                .assertNext(summary -> Assertions.assertEquals(1, summary.probed()))
                .verifyComplete();

        // 运维点"立即探测"不该被别的实例的锁挡住
        Mockito.verify(valueOperations, Mockito.never())
                .setIfAbsent(Mockito.anyString(), Mockito.anyString(), Mockito.any(Duration.class));
        Mockito.verify(valueOperations, Mockito.never()).get(Mockito.anyString());
    }

    @Test
    void shouldIgnoreOverlappingRoundOnTheSameInstance() {
        CountDownLatch entered = new CountDownLatch(1);
        Mockito.when(upstreamModelProbe.probeWithDiagnosis(PROVIDER))
                .thenReturn(Mono.<ProbeOutcome>never().doOnSubscribe((Subscription subscription) -> entered.countDown()));
        ChannelProbeService service = service();

        Disposable running = service.probeAll().subscribe();
        try {
            Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS), "第一轮应已进入探测");
            StepVerifier.create(service.probeAll())
                    .assertNext(summary -> Assertions.assertTrue(summary.skippedByLock()))
                    .verifyComplete();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            Assertions.fail(ex);
        } finally {
            running.dispose();
        }
    }

    @Test
    void shouldReleaseItsOwnLockAndStartTheNextRound() {
        probeReturns(ProbeOutcome.success(PROVIDER, List.of("gpt-4o"), 3L));
        AtomicReference<String> lockValue = new AtomicReference<>();
        Mockito.lenient().when(valueOperations.setIfAbsent(Mockito.eq(ChannelProbeService.LOCK_KEY),
                        Mockito.anyString(), Mockito.any(Duration.class)))
                .thenAnswer(invocation -> {
                    lockValue.set(invocation.getArgument(1));
                    return true;
                });
        Mockito.lenient().when(valueOperations.get(ChannelProbeService.LOCK_KEY)).thenAnswer(i -> lockValue.get());
        ChannelProbeService service = service();

        StepVerifier.create(service.probeAll()).expectNextCount(1).verifyComplete();
        Mockito.verify(redis, Mockito.timeout(3000)).delete(ChannelProbeService.LOCK_KEY);

        // 上一轮的 running 标记必须已经清掉，否则这个实例此后再也不会探测
        StepVerifier.create(service.probeAll())
                .assertNext(summary -> {
                    Assertions.assertEquals(1, summary.probed());
                    Assertions.assertFalse(summary.skippedByLock());
                })
                .verifyComplete();
    }

    @Test
    void shouldNotReleaseLockThatBelongsToAnotherInstance() {
        probeReturns(ProbeOutcome.success(PROVIDER, List.of("gpt-4o"), 3L));
        Mockito.when(valueOperations.get(Mockito.anyString())).thenReturn("someone-else:abc");

        StepVerifier.create(service().probeAll()).expectNextCount(1).verifyComplete();

        // 释放锁在弹性线程池上跑，立即 verify(never) 会在它执行之前就通过 —— 用 after 等一个窗口
        Mockito.verify(redis, Mockito.after(500).never()).delete(Mockito.anyString());
    }

    @Test
    void shouldProbeOnDemandEvenWhenChannelIsCoolingDown() {
        healthDownCooldownIn(Duration.ofMinutes(5));
        probeReturns(ProbeOutcome.success(PROVIDER, List.of("gpt-4o"), 4L));

        StepVerifier.create(service().probeOne(PROVIDER))
                .assertNext(outcome -> Assertions.assertTrue(outcome.ok()))
                .verifyComplete();

        // 运维就是想现在知道它通不通，冷却不该挡住手工探测
        Mockito.verify(upstreamModelProbe).probeWithDiagnosis(PROVIDER);
    }

    @Test
    void shouldDoNothingWhenNoChannelIsConfigured() {
        properties.getUpstream().setProviderBaseUrl(new LinkedHashMap<>());
        properties.getUpstream().setDefaultProvider("openai");
        properties.getRouting().setProviderPriority(List.of());

        StepVerifier.create(service().probeAll())
                .assertNext(summary -> {
                    Assertions.assertEquals(0, summary.probed());
                    Assertions.assertTrue(summary.skipped().isEmpty());
                })
                .verifyComplete();

        Mockito.verify(upstreamModelProbe, Mockito.never()).probeWithDiagnosis(Mockito.anyString());
    }

    @Test
    void shouldReportThatManualToggleCannotBePersistedWithoutRepository() {
        StepVerifier.create(service(null).setManualDisabled(PROVIDER, true))
                .assertNext(Assertions::assertFalse)
                .verifyComplete();

        Mockito.verify(repository, Mockito.never()).markManuallyDisabled(Mockito.anyString());
    }

    @Test
    void shouldPersistManualDisableAndRefreshSnapshotImmediately() {
        ChannelProbeService service = service();
        StepVerifier.create(service.setManualDisabled(PROVIDER, true))
                .assertNext(Assertions::assertTrue)
                .verifyComplete();

        Mockito.verify(repository).markManuallyDisabled(PROVIDER);
        Mockito.verify(runtimeConfigVersions).publish(ChannelHealthReloader.KEY, 7L);
        // 必须立刻重建快照：不然刚被禁用的渠道还会被路由选中几十秒
        Mockito.verify(channelHealthRegistry).refresh();
    }

    @Test
    void shouldClearAutomaticConclusionWhenManuallyEnabling() {
        StepVerifier.create(service().setManualDisabled(PROVIDER, false))
                .assertNext(Assertions::assertTrue)
                .verifyComplete();

        // 只把 manual_disabled 置 0 是不够的：被探测判 DOWN 的渠道会看起来像"按钮失效"
        Mockito.verify(repository).markManuallyEnabled(PROVIDER);
        Mockito.verify(repository, Mockito.never()).markManuallyDisabled(Mockito.anyString());
    }

    @Test
    void shouldTreatOnlyChannelBrokenErrorsAsFailures() {
        Assertions.assertFalse(ChannelProbeService.countsAsFailure(ProbeErrorType.OK));
        Assertions.assertFalse(ChannelProbeService.countsAsFailure(ProbeErrorType.RATE_LIMITED));
        Assertions.assertFalse(ChannelProbeService.countsAsFailure(ProbeErrorType.NO_CREDENTIAL));
        Assertions.assertTrue(ChannelProbeService.countsAsFailure(ProbeErrorType.UNAUTHORIZED));
        Assertions.assertTrue(ChannelProbeService.countsAsFailure(ProbeErrorType.UPSTREAM_5XX));
        Assertions.assertTrue(ChannelProbeService.countsAsFailure(ProbeErrorType.TIMEOUT));
        Assertions.assertTrue(ChannelProbeService.countsAsFailure(ProbeErrorType.CONNECT_FAILED));
        Assertions.assertTrue(ChannelProbeService.countsAsFailure(ProbeErrorType.BAD_RESPONSE));
    }

    private ChannelProbeService service() {
        return service(repository);
    }

    private ChannelProbeService service(ProviderHealthRepository target) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProviderHealthRepository> provider = Mockito.mock(ObjectProvider.class);
        Mockito.lenient().when(provider.getIfAvailable()).thenReturn(target);
        return new ChannelProbeService(properties, upstreamModelProbe, channelHealthRegistry,
                provider, runtimeConfigVersions, redis);
    }

    private void probeReturns(ProbeOutcome outcome) {
        Mockito.lenient().when(upstreamModelProbe.probeWithDiagnosis(PROVIDER)).thenReturn(Mono.just(outcome));
    }

    private void healthDownCooldownIn(Duration cooldown) {
        Mockito.when(channelHealthRegistry.find(PROVIDER)).thenReturn(
                new ChannelHealthRegistry.ChannelHealth(PROVIDER, true, false, 3, 0, "CONNECT_FAILED",
                        LocalDateTime.now(), LocalDateTime.now().plus(cooldown), 30L));
    }
}
