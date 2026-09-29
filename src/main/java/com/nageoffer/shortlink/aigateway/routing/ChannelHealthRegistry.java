package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderHealthEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderHealthRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 渠道健康快照：探测结论的读路径。
 * <p>
 * 与通道组一致，采用"DB 为真源 + 内存快照"：路由在每请求热路径上只读内存
 * （{@link #allows} 是一次 map 查找，不做任何 IO），变更由探测任务或管理面写库后触发
 * {@link #refresh()}。DB 不可用时快照为空，此时 {@link #allows} 对其余渠道返回 true
 * （fail-open）——这条很重要：探测体系本身坏掉不该让所有渠道一起不可路由。
 * <p>
 * 两种"不可用"的优先级：
 * <ol>
 *   <li>yml 的 {@code probe.channel-enabled.<provider>=false}：静态禁用，跟着发布走，任何时候都生效；</li>
 *   <li>{@code provider_health} 里的 {@code status='DOWN'} 或 {@code manual_disabled=1}。</li>
 * </ol>
 * 快照里没有该渠道的行时按可用处理，理由同上（漏一行不该等于静默禁用）。
 */
@Slf4j
@Service
public class ChannelHealthRegistry implements ChannelHealthView {

    static final String SOURCE_DATABASE = "database";

    static final String SOURCE_MEMORY = "memory";

    private final AiGatewayProperties properties;

    private final ObjectProvider<ProviderHealthRepository> repository;

    private volatile Map<String, ChannelHealth> snapshot = Map.of();

    private volatile String snapshotSource = SOURCE_MEMORY;

    @Autowired
    public ChannelHealthRegistry(AiGatewayProperties properties,
                                 ObjectProvider<ProviderHealthRepository> repository) {
        this.properties = properties;
        this.repository = repository;
    }

    /**
     * 不做健康过滤的构造方式（无 DB 场景与单测）。
     */
    public ChannelHealthRegistry(AiGatewayProperties properties) {
        this(properties, null);
    }

    @PostConstruct
    void init() {
        refresh().subscribe(ignored -> {
        }, ex -> log.warn("failed to load channel health from database, keep empty snapshot: {}",
                ex.getMessage()));
    }

    /**
     * 从 DB 重新加载快照。
     * <p>
     * 整表替换而不是增量更新：一次探测可能同时改多个渠道的状态，
     * 增量更新在"某个渠道本轮没被探到"时会留下过期结论。
     */
    public Mono<Void> refresh() {
        ProviderHealthRepository healthRepository = repository == null ? null : repository.getIfAvailable();
        if (healthRepository == null) {
            this.snapshot = Map.of();
            this.snapshotSource = SOURCE_MEMORY;
            return Mono.empty();
        }
        return healthRepository.findAll()
                .collectList()
                .doOnNext(this::applySnapshot)
                .then();
    }

    /**
     * 用一批行整体替换快照。DB 抛错时不会走到这里，因此旧快照得以保留。
     */
    void applySnapshot(List<ProviderHealthEntity> rows) {
        Map<String, ChannelHealth> next = new LinkedHashMap<>();
        for (ProviderHealthEntity row : rows) {
            if (row == null || row.getProvider() == null) {
                continue;
            }
            next.put(row.getProvider(), ChannelHealth.from(row));
        }
        this.snapshot = Map.copyOf(next);
        this.snapshotSource = SOURCE_DATABASE;
    }

    @Override
    public boolean allows(String provider) {
        if (provider == null) {
            return false;
        }
        if (Boolean.FALSE.equals(properties.getProbe().getChannelEnabled().get(provider))) {
            return false;
        }
        ChannelHealth health = snapshot.get(provider);
        return health == null || health.usable();
    }

    /**
     * 当前被判为不可用的渠道集合（yml 静态禁用 ∪ 快照里的 DOWN/人工禁用）。
     * 管理面用它回答"为什么这条渠道不见了"。
     */
    public Set<String> disabledProviders() {
        Set<String> disabled = new LinkedHashSet<>();
        properties.getProbe().getChannelEnabled().forEach((provider, enabled) -> {
            if (Boolean.FALSE.equals(enabled)) {
                disabled.add(provider);
            }
        });
        snapshot.values().stream()
                .filter(health -> !health.usable())
                .map(ChannelHealth::provider)
                .forEach(disabled::add);
        return disabled;
    }

    public Map<String, ChannelHealth> snapshot() {
        return snapshot;
    }

    public ChannelHealth find(String provider) {
        return snapshot.get(provider);
    }

    /**
     * {@code database} / {@code memory}。memory 表示没有接到仓储，此时快照恒为空且不做过滤。
     */
    public String source() {
        return snapshotSource;
    }

    /**
     * 快照是否来自数据库。为 false 时人工禁用无处存放、探测结论也不会跨实例同步，
     * 管理面据此提示降级（见 {@code AiChannelHealthController} 的 {@code degradation}）。
     */
    public boolean databaseBacked() {
        return SOURCE_DATABASE.equals(snapshotSource);
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 单个渠道的健康快照值。
     *
     * @param down                探测结论为 DOWN
     * @param manualDisabled      运维手动禁用
     * @param disabledUntil       DOWN 的冷却到期时间；冷却未过时不会被重新探测
     * @param consecutiveFailures 连续失败次数
     * @param consecutiveSuccesses 连续成功次数
     */
    public record ChannelHealth(String provider,
                                boolean down,
                                boolean manualDisabled,
                                int consecutiveFailures,
                                int consecutiveSuccesses,
                                String lastError,
                                LocalDateTime lastCheckedAt,
                                LocalDateTime disabledUntil,
                                Long latencyMillis) {

        /**
         * 实体 → 快照值的唯一映射点。计数列在库里可空，这里统一收敛成 0。
         */
        public static ChannelHealth from(ProviderHealthEntity entity) {
            return new ChannelHealth(
                    entity.getProvider(),
                    entity.isDown(),
                    entity.isManuallyDisabled(),
                    nullSafe(entity.getConsecutiveFailures()),
                    nullSafe(entity.getConsecutiveSuccesses()),
                    entity.getLastError(),
                    entity.getLastCheckedAt(),
                    entity.getDisabledUntil(),
                    entity.getLatencyMillis());
        }

        /**
         * 是否可以被选进候选集。DOWN 与人工禁用是"或"的关系：
         * 前者是探测结论，后者是运维意志，任一为真都不该路由过去。
         */
        public boolean usable() {
            return !down && !manualDisabled;
        }

        /**
         * 冷却是否已过。冷却未过的 DOWN 渠道不会被重新探测，免得对已经宕机的上游持续打请求。
         */
        public boolean cooldownElapsed() {
            return disabledUntil == null || !disabledUntil.isAfter(LocalDateTime.now());
        }
    }
}
