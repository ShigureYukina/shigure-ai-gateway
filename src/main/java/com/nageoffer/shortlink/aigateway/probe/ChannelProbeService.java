package com.nageoffer.shortlink.aigateway.probe;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsKeys;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderHealthRepository;
import com.nageoffer.shortlink.aigateway.routing.ChannelHealthRegistry;
import com.nageoffer.shortlink.aigateway.routing.RouteCandidates;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigVersions;
import com.nageoffer.shortlink.aigateway.sync.UpstreamModelProbe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 主动探测：定时探活各渠道，连续失败自动禁用、恢复后自动放回候选集。
 * <p>
 * 只做"探测 → 落库 → 通知"，不做路由决策：是否可用由 {@link ChannelHealthRegistry} 的快照回答，
 * 路由热路径读快照，两者通过 {@code provider_health} 表解耦。
 * <p>
 * <b>三层幂等</b>，因为多实例部署下同一时刻可能有多个实例在探同一批渠道：
 * <ol>
 *   <li>本实例 {@code AtomicBoolean}：防止 {@code @Scheduled} 与手工触发重入（最便宜的一层）；</li>
 *   <li>Redis {@code SET NX PX}（TTL 取探测周期的 0.9 倍）：同一周期只让一个实例真的打上游。
 *       Redis 不可用时降级为"各自探"，并在 {@link #redisDegraded()} 上留痕；</li>
 *   <li>SQL 条件更新兜底（{@code WHERE status='UP' AND consecutive_failures >= :n}）：
 *       即使前两层都失效，也只有一次状态迁移能成功。</li>
 * </ol>
 * 第三层是唯一必需的那层，前两层是省上游请求的优化 —— 所以锁的原子性不必做到完美。
 * <p>
 * <b>不算失败的探测</b>：限流（429）与未配凭证不改变任何计数。前者说明渠道是活的，
 * 后者是配置问题而非上游故障，把它算成失败会把一个完好的渠道关掉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelProbeService {

    /**
     * 跨实例探测锁。与其他低频键同前缀（{@link AiGatewayMetricsKeys#PREFIX}）。
     */
    static final String LOCK_KEY = AiGatewayMetricsKeys.PREFIX + "probe:lock";

    /**
     * 探测周期配成 0 或负数时的锁 TTL 兜底值，避免 {@code SET ... PX 0} 直接报错。
     */
    private static final Duration MIN_LOCK_TTL = Duration.ofMinutes(1);

    private final AiGatewayProperties properties;

    private final UpstreamModelProbe upstreamModelProbe;

    private final ChannelHealthRegistry channelHealthRegistry;

    /**
     * 为 {@code null} 或取不到时表示没有持久化能力：探测照跑，但结论只记日志不落库
     * （本地无 DB 的默认形态），此时也不会向其他实例广播版本号。
     */
    private final ObjectProvider<ProviderHealthRepository> repositoryProvider;

    private final RuntimeConfigVersions runtimeConfigVersions;

    private final StringRedisTemplate stringRedisTemplate;

    private final AtomicBoolean probing = new AtomicBoolean(false);

    private final AtomicBoolean redisDegraded = new AtomicBoolean(false);

    private final AtomicReference<ProbeSummary> lastSummary = new AtomicReference<>();

    /**
     * 锁的值：本实例标识 + 随机数。先读后删时用它确认"删的是自己那把锁"。
     */
    private final String instanceId = UUID.randomUUID().toString();

    /**
     * 探测一轮全部"已配置"渠道。
     * <p>
     * 用 {@link RouteCandidates#configured}（不带健康视图）而不是候选集：被禁用、冷却中的渠道
     * 本来就是本轮要重新探活的对象，用健康视图过滤会导致它们永远回不来。
     */
    public Mono<ProbeSummary> probeAll() {
        return probeAll(false);
    }

    /**
     * @param forced 跳过跨实例锁。仅管理面的"立即探测"用：运维点按钮时不该被别的实例的锁挡住，
     *               而 SQL 条件更新保证并发强制探测也不会把状态改错。
     */
    public Mono<ProbeSummary> probeAll(boolean forced) {
        // 重入判定与解锁都在 defer 里，用"订阅时刻"而不是"装配时刻"的语义：
        // 装配了却不订阅（例如调用方把 Mono 存起来又丢弃）不能让 running 永久为真，
        // 否则这个实例此后再也不会探测，而且不会有任何报错。
        return Mono.defer(() -> {
            if (!probing.compareAndSet(false, true)) {
                // 本分支没有占用 running，绝不能挂 doFinally 去清它 ——
                // 那会把正在跑的那一轮的标记清掉，放进来第二轮并发探测
                return Mono.just(ProbeSummary.held());
            }
            if (forced) {
                return runRound().doFinally(signal -> probing.set(false));
            }
            String token = instanceId + ":" + UUID.randomUUID();
            // 释放只挂在"真的拿到锁"的那条分支上。挂在外层会让没抢到锁的实例去删别人的锁，
            // 于是每个实例都在替别人解锁，跨实例去重直接失效（而且看不出任何异常）
            return acquireLock(token)
                    .flatMap(acquired -> acquired
                            ? runRound().doFinally(signal ->
                                    // 释放是同步 Redis 调用，扔到弹性线程池上，避免占住订阅线程（可能是 Netty 事件循环）
                                    Schedulers.boundedElastic().schedule(() -> releaseLock(token)))
                            : Mono.just(ProbeSummary.held()))
                    .doFinally(signal -> probing.set(false));
        })
                // 订阅切到弹性线程池：锁的获取/释放是同步 Redis 调用，而手工触发是从 Netty 线程进来的，
                // 同步阻塞会占住事件循环
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 手工探测单个渠道，<b>并且记录这次结果</b>。
     * <p>
     * 刻意不跳过冷却中与已手动禁用的渠道（探测本身无害，运维就是想现在知道它通不通）；
     * 但落库侧对 {@code manual_disabled = 1} 的行有守卫，写不进去 ——
     * 所以"手动禁用"不会被一次手工探测悄悄放开。
     */
    public Mono<ProbeOutcome> probeOne(String provider) {
        return probeAndRecord(provider, false).map(Step::outcome);
    }

    /**
     * 控制台手动禁用/恢复。
     *
     * @return 是否真的写进了库；没有持久化能力时返回 false（调用方据此提示"仅本实例生效"）
     */
    public Mono<Boolean> setManualDisabled(String provider, boolean disabled) {
        ProviderHealthRepository repository = repository();
        if (repository == null) {
            return Mono.just(false);
        }
        // 先 ensureRow：未探过的渠道还没有行，直接 UPDATE 会静默影响 0 行，
        // 运维会看到"按钮点了没反应"
        return repository.ensureRow(provider)
                .then(Mono.defer(() -> disabled
                        ? repository.markManuallyDisabled(provider)
                        : repository.markManuallyEnabled(provider)))
                .flatMap(rows -> publishHealthChange()
                        .then(refreshSnapshot())
                        .thenReturn(true))
                .onErrorResume(ex -> {
                    log.warn("failed to set manual_disabled={} for {}: {}", disabled, provider, ex.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * 最近一轮探测的汇总。<b>可能为 null</b>（还没探过）。
     */
    public ProbeSummary lastSummary() {
        return lastSummary.get();
    }

    /**
     * Redis 是否处于不可用降级态。管理面据此提示"跨实例协调已失效"。
     */
    public boolean redisDegraded() {
        return redisDegraded.get();
    }

    private Mono<ProbeSummary> runRound() {
        Set<String> providers = RouteCandidates.configured(properties);
        if (providers.isEmpty()) {
            return refreshSnapshot().then(Mono.fromSupplier(() -> summarize(List.of())));
        }
        // concatMap 而不是 flatMap：串行探，避免同一时刻对多个上游并发打请求，
        // 也让日志与汇总顺序稳定（渠道数不多，最坏耗时 = 渠道数 × probe.timeout，远小于探测周期）
        return Flux.fromIterable(providers)
                .concatMap(provider -> probeAndRecord(provider, true))
                .collectList()
                .flatMap(steps -> refreshSnapshot().then(Mono.fromSupplier(() -> summarize(steps))));
    }

    private Mono<Step> probeAndRecord(String provider, boolean honorCooldown) {
        if (honorCooldown) {
            String reason = skipReason(provider);
            if (reason != null) {
                return Mono.just(Step.skipped(provider, reason));
            }
        }
        ProviderHealthRepository repository = repository();
        Mono<Void> ensure = repository == null ? Mono.empty() : repository.ensureRow(provider).then();
        return ensure.then(upstreamModelProbe.probeWithDiagnosis(provider))
                .flatMap(outcome -> record(repository, provider, outcome)
                        .map(transition -> Step.of(provider, outcome, transition.down(), transition.up())));
    }

    /**
     * 本轮该不该跳过这个渠道。只有定时轮转才问这个问题（手工探测一律执行）。
     */
    private String skipReason(String provider) {
        ChannelHealthRegistry.ChannelHealth health = channelHealthRegistry.find(provider);
        if (health == null) {
            // 快照里没有 = 还没探过 = 正是该探的
            return null;
        }
        if (health.manualDisabled()) {
            return "已手动禁用";
        }
        if (health.down() && !health.cooldownElapsed()) {
            // 冷却期内不再打已经判定宕机的上游，免得对着故障上游持续发请求
            return "冷却中";
        }
        return null;
    }

    private Mono<Transition> record(ProviderHealthRepository repository, String provider, ProbeOutcome outcome) {
        if (repository == null) {
            return Mono.just(Transition.NONE);
        }
        if (outcome.ok()) {
            return repository.recordSuccess(provider, outcome.latencyMillis())
                    .flatMap(rows -> reallyChanged(rows)
                            ? recoverIfNeeded(repository, provider)
                            : Mono.just(Transition.NONE));
        }
        if (!countsAsFailure(outcome.errorType())) {
            log.debug("inconclusive probe for {}: {} ({}) — counters untouched",
                    provider, outcome.errorType(), outcome.message());
            return Mono.just(Transition.NONE);
        }
        return repository.recordFailure(provider, outcome.describeError(), outcome.latencyMillis())
                .flatMap(rows -> reallyChanged(rows)
                        ? disableIfNeeded(repository, provider)
                        : Mono.just(Transition.NONE));
    }

    private Mono<Transition> disableIfNeeded(ProviderHealthRepository repository, String provider) {
        int threshold = properties.getProbe().getFailureThreshold();
        Duration cooldown = properties.getProbe().getDownCooldown();
        LocalDateTime disabledUntil = LocalDateTime.now().plus(cooldown == null ? Duration.ZERO : cooldown);
        return repository.markDownIfThresholdReached(provider, threshold, disabledUntil)
                .flatMap(rows -> {
                    if (!reallyChanged(rows)) {
                        return Mono.just(Transition.NONE);
                    }
                    log.warn("channel {} disabled by probe: consecutive failures reached threshold {},"
                                    + " next probe allowed after {}",
                            provider, threshold, cooldown);
                    return publishHealthChange().thenReturn(Transition.DOWN);
                });
    }

    private Mono<Transition> recoverIfNeeded(ProviderHealthRepository repository, String provider) {
        int threshold = properties.getProbe().getRecoveryThreshold();
        return repository.markUpIfRecovered(provider, threshold)
                .flatMap(rows -> {
                    if (!reallyChanged(rows)) {
                        return Mono.just(Transition.NONE);
                    }
                    log.info("channel {} recovered by probe: {} consecutive successes reached threshold {}",
                            provider, threshold, threshold);
                    return publishHealthChange().thenReturn(Transition.UP);
                });
    }

    /**
     * 探测状态变了之后通知其他实例：换一个版本令牌，各实例的 {@code RuntimeConfigPoller}
     * 下一轮就会回调 {@link ChannelHealthReloader#reload()}。
     * <p>
     * 只在状态<b>迁移</b>时发（而不是每次写计数都发）：路由只关心能不能选中，
     * 计数变化不值得让所有实例重读一遍整张表。
     */
    private Mono<Void> publishHealthChange() {
        return runtimeConfigVersions.nextToken()
                .flatMap(token -> runtimeConfigVersions.publish(ChannelHealthReloader.KEY, token))
                .onErrorResume(ex -> {
                    log.warn("failed to publish channel health version: {}", ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 重建本实例快照。本轮自己改过的状态必须马上生效 —— 不能等下一次配置轮询，
     * 否则刚被禁用的渠道还会被路由选中几十秒。
     */
    private Mono<Void> refreshSnapshot() {
        return channelHealthRegistry.refresh()
                .onErrorResume(ex -> {
                    log.warn("failed to refresh channel health snapshot: {}", ex.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Boolean> acquireLock(String token) {
        return Mono.fromCallable(() -> {
            try {
                Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY, token, lockTtl());
                redisDegraded.set(false);
                // setIfAbsent 在管道/事务上下文里可能返回 null，此时按"拿到锁"处理：
                // 多探一轮的代价远小于"因为拿不到锁就永远不探测"
                return !Boolean.FALSE.equals(acquired);
            } catch (Exception ex) {
                redisDegraded.set(true);
                log.warn("probe lock unavailable, probing without cross-instance coordination: {}", ex.getMessage());
                return true;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 先读后删而不是 Lua 原子比较。<b>这是刻意的取舍</b>：锁只是"别让所有实例同时打上游"的优化，
     * 极小的竞态窗口最坏让另一个实例的锁被提前释放（多一轮重复探测），
     * 而状态迁移由 SQL 条件更新兜底，不会因为重复探测出错。为此引入一段 Lua 脚本不划算。
     */
    private void releaseLock(String token) {
        try {
            if (token.equals(stringRedisTemplate.opsForValue().get(LOCK_KEY))) {
                stringRedisTemplate.delete(LOCK_KEY);
            }
        } catch (Exception ex) {
            log.debug("failed to release probe lock: {}", ex.getMessage());
        }
    }

    private Duration lockTtl() {
        Duration interval = properties.getProbe().getInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return MIN_LOCK_TTL;
        }
        return interval.multipliedBy(9).dividedBy(10);
    }

    private ProviderHealthRepository repository() {
        return repositoryProvider == null ? null : repositoryProvider.getIfAvailable();
    }

    private static boolean reallyChanged(Integer rows) {
        return rows != null && rows > 0;
    }

    /**
     * 这次失败该不该计入禁用计数。
     * <p>
     * 限流与未配凭证不计：前者说明渠道是活的（只是此刻排队），后者是配置没补齐，
     * 把这两种算成失败会把一个完好的渠道关掉，而恢复它还得再等一个冷却周期。
     */
    static boolean countsAsFailure(ProbeErrorType type) {
        return type != ProbeErrorType.OK
                && type != ProbeErrorType.RATE_LIMITED
                && type != ProbeErrorType.NO_CREDENTIAL;
    }

    private ProbeSummary summarize(List<Step> steps) {
        int probed = 0;
        int healthy = 0;
        int unhealthy = 0;
        int inconclusive = 0;
        List<String> skipped = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        List<String> recovered = new ArrayList<>();
        for (Step step : steps) {
            if (step.skipReason() != null) {
                skipped.add(step.provider() + ": " + step.skipReason());
                continue;
            }
            probed++;
            if (step.outcome().ok()) {
                healthy++;
            } else if (countsAsFailure(step.outcome().errorType())) {
                unhealthy++;
            } else {
                inconclusive++;
            }
            if (step.downTransition()) {
                disabled.add(step.provider());
            }
            if (step.upTransition()) {
                recovered.add(step.provider());
            }
        }
        ProbeSummary summary = new ProbeSummary(probed, healthy, unhealthy, inconclusive,
                skipped, disabled, recovered, false, Instant.now());
        if (summary.disabled().isEmpty() && summary.recovered().isEmpty()) {
            log.info("channel probe finished: probed={} healthy={} unhealthy={} inconclusive={} skipped={}",
                    probed, healthy, unhealthy, inconclusive, skipped.size());
        }
        lastSummary.set(summary);
        return summary;
    }

    /**
     * 单个渠道一轮的处理结果：探测结论（被跳过时为 null）、跳过原因、以及本轮是否发生了状态迁移。
     */
    private record Step(String provider,
                        ProbeOutcome outcome,
                        String skipReason,
                        boolean downTransition,
                        boolean upTransition) {

        static Step skipped(String provider, String reason) {
            return new Step(provider, null, reason, false, false);
        }

        static Step of(String provider, ProbeOutcome outcome, boolean down, boolean up) {
            return new Step(provider, outcome, null, down, up);
        }
    }

    private record Transition(boolean down, boolean up) {

        static final Transition NONE = new Transition(false, false);

        static final Transition DOWN = new Transition(true, false);

        static final Transition UP = new Transition(false, true);
    }
}
