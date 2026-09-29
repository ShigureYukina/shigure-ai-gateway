package com.nageoffer.shortlink.aigateway.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.entity.RuntimeConfigEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.RuntimeConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时配置中心：DB 为真源，把六个域的 JSON 快照应用回 {@link AiGatewayProperties}。
 * <p>
 * 三条行为约定（会直接影响运维的感受，改动前先读）：
 * <ul>
 *   <li><b>首次写入会把当时的 yml 值一并冻进 DB</b>。此后改 yml 对已落库的键不再生效，
 *       因为逐字段 apply 是"库里有就覆盖"。未落库的键仍然吃 yml —— 这正是"缺键保持原值"的价值。
 *       要回到 yml 值，走 {@code DELETE /v1/runtime-config/{domain}}。</li>
 *   <li><b>DB 不可用时不清空</b>：读失败保留当前内存值，写失败由调用方回滚。</li>
 *   <li><b>没有 repository 就退化为纯内存</b>：R2DBC 仓库只在
 *       {@code tenant.persistence.enabled=true} 时注册，本地默认跑的是这条路。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeConfigService {

    /**
     * 启动期加载的硬上限：DB 不可达时也不能让应用起不来。
     */
    private static final Duration STARTUP_LOAD_TIMEOUT = Duration.ofSeconds(5);

    private static final TypeReference<Map<String, Object>> SNAPSHOT_TYPE = new TypeReference<>() {
    };

    private final AiGatewayProperties properties;

    private final ObjectProvider<RuntimeConfigRepository> repositoryProvider;

    private final ObjectMapper objectMapper;

    private final RuntimeConfigVersions runtimeConfigVersions;

    /**
     * 启动时的 yml 基线：删除库里的行之后回落到这里。
     * <p>
     * 只在 {@code @PostConstruct} 里写、之后只读 —— 轮询线程 10 秒后才起来，读到的是稳定的 Map。
     */
    private final Map<RuntimeConfigDomain, Map<String, Object>> baseline = new EnumMap<>(RuntimeConfigDomain.class);

    /**
     * 最近一次"成功生效"的快照，写库失败时回滚到这里。
     * <p>
     * 为什么不回滚到 {@link #baseline}：基线是启动时的 yml 值，而"上一次控制台写入"可能早就
     * 把它覆盖掉了。回滚到基线等于把运维上一步的配置也一起撤销，比不回滚更难排查。
     * <p>
     * 用 {@code ConcurrentHashMap} 而不是 {@code EnumMap}：写方有两个线程 ——
     * 轮询线程（{@code reload}）与管理面请求线程（{@code persist}／{@code rollback}），
     * {@code EnumMap} 并发 put 会损坏内部结构。
     */
    private final Map<RuntimeConfigDomain, Map<String, Object>> applied = new ConcurrentHashMap<>();

    /**
     * 当前取值来自 DB 的域，用于 {@code GET /v1/runtime-config} 的 {@code source} 字段。
     */
    private final Set<RuntimeConfigDomain> fromDatabase = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void initialize() {
        for (RuntimeConfigDomain domain : RuntimeConfigDomain.values()) {
            Map<String, Object> snapshot = RuntimeConfigSupport.extract(domain, properties);
            baseline.put(domain, snapshot);
            applied.put(domain, snapshot);
        }
        if (!persistable()) {
            log.info("runtime config persistence unavailable (R2DBC repository not registered), "
                    + "management writes take effect in this instance only");
            return;
        }
        try {
            loadAll().block(STARTUP_LOAD_TIMEOUT);
            log.info("runtime config loaded from database for domains={}", fromDatabase);
        } catch (Exception ex) {
            log.warn("failed to load runtime config from database at startup, keeping yml values: {}", ex.getMessage());
        }
    }

    /**
     * 是否具备落库能力。为 false 时所有写操作只改本实例内存。
     */
    public boolean persistable() {
        return repositoryProvider.getIfAvailable() != null;
    }

    /**
     * 把全部域按库里的最新内容重放一遍。
     */
    public Mono<Void> loadAll() {
        return Flux.fromArray(RuntimeConfigDomain.values())
                .concatMap(this::reload)
                .then();
    }

    /**
     * 重载单个域。库里没有该域的行 → 回落到启动时的 yml 基线。
     */
    public Mono<Void> reload(RuntimeConfigDomain domain) {
        RuntimeConfigRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null) {
            return Mono.empty();
        }
        return repository.findByDomain(domain.key())
                .doOnNext(entity -> {
                    applySnapshot(domain, entity.getConfigJson());
                    fromDatabase.add(domain);
                })
                .switchIfEmpty(Mono.fromRunnable(() -> restoreBaseline(domain)))
                .then()
                .onErrorResume(ex -> {
                    // 也可能是"JSON 解析失败"这类发生在 apply 中途的错误：回滚一次保证不留下半个快照
                    rollback(domain);
                    log.warn("failed to reload runtime config domain={}, keeping current in-memory values: {}",
                            domain.key(), ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 落库并广播版本号。调用方（{@link RuntimeConfigPublisher}）负责保证内存已先行生效。
     * <p>
     * 不记录"谁改的"：actor 由管理面过滤器写进审计日志（{@code /v1/audit/logs}），
     * 为一个展示字段给 6 个 controller 都加上 {@code ServerWebExchange} 参数不划算。
     *
     * @return 落库后的版本令牌；没有 repository 时为空
     */
    public Mono<Long> persist(RuntimeConfigDomain domain, Map<String, Object> snapshot) {
        RuntimeConfigRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null) {
            return Mono.empty();
        }
        String json = writeJson(domain, snapshot);
        return runtimeConfigVersions.nextToken()
                .flatMap(token -> findOrNew(repository, domain, json, token)
                        .then(runtimeConfigVersions.publish(domain.key(), token))
                        .thenReturn(token))
                .doOnSuccess(token -> {
                    fromDatabase.add(domain);
                    snapshotCurrent(domain);
                });
    }

    /**
     * 删掉该域的行（等价"恢复出厂"）：内存立刻回到 yml 基线，其他实例在下一次轮询跟随。
     */
    public Mono<Void> delete(RuntimeConfigDomain domain) {
        RuntimeConfigRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null) {
            restoreBaseline(domain);
            return Mono.empty();
        }
        return repository.deleteById(domain.key())
                .then(runtimeConfigVersions.nextToken())
                .flatMap(token -> runtimeConfigVersions.publish(domain.key(), token))
                .doOnSuccess(ignored -> restoreBaseline(domain));
    }

    /**
     * 回到启动时的 yml 值。
     */
    public void restoreBaseline(RuntimeConfigDomain domain) {
        Map<String, Object> snapshot = baseline.get(domain);
        if (snapshot != null) {
            RuntimeConfigSupport.apply(domain, properties, snapshot);
            applied.put(domain, snapshot);
        }
        fromDatabase.remove(domain);
    }

    /**
     * 回滚到最近一次成功生效的值。写库失败时由 {@link RuntimeConfigPublisher} 调用。
     * <p>
     * 刻意不重新读库：写库失败往往正是因为库不可用，此时再去读一次也是白读，
     * 反而会让内存停在"已经被改坏"的状态上，而调用方却收到了 400。
     */
    public void rollback(RuntimeConfigDomain domain) {
        Map<String, Object> snapshot = applied.get(domain);
        if (snapshot != null) {
            RuntimeConfigSupport.apply(domain, properties, snapshot);
        }
    }

    /**
     * 六个域当前生效值的可读视图，供 {@code GET /v1/runtime-config} 用。
     */
    public Mono<List<Map<String, Object>>> describe() {
        RuntimeConfigRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null) {
            return Mono.just(describeInMemory(Map.of()));
        }
        return repository.findAll()
                .collectMap(RuntimeConfigEntity::getDomain)
                .map(this::describeInMemory)
                .onErrorResume(ex -> {
                    log.warn("failed to read runtime config rows for describe: {}", ex.getMessage());
                    return Mono.just(describeInMemory(Map.of()));
                });
    }

    /**
     * 读取某个域落库的版本（供管理面展示；读不到返回 0）。
     */
    public Mono<Long> storedVersion(RuntimeConfigDomain domain) {
        RuntimeConfigRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null) {
            return Mono.just(0L);
        }
        return repository.findByDomain(domain.key())
                .map(entity -> entity.getVersion() == null ? 0L : entity.getVersion())
                .defaultIfEmpty(0L)
                .onErrorReturn(0L);
    }

    private Mono<RuntimeConfigEntity> findOrNew(RuntimeConfigRepository repository,
                                                RuntimeConfigDomain domain,
                                                String json,
                                                long token) {
        return repository.findByDomain(domain.key())
                .flatMap(existing -> {
                    existing.setConfigJson(json);
                    existing.setVersion(token);
                    return repository.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    RuntimeConfigEntity created = new RuntimeConfigEntity();
                    created.setDomain(domain.key());
                    created.setConfigJson(json);
                    created.setVersion(token);
                    return repository.save(created);
                }));
    }

    private void applySnapshot(RuntimeConfigDomain domain, String json) {
        Map<String, Object> values;
        try {
            values = objectMapper.readValue(json, SNAPSHOT_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("runtime config snapshot is not a flat JSON object: domain=" + domain.key(), ex);
        }
        RuntimeConfigSupport.apply(domain, properties, values);
        snapshotCurrent(domain);
    }

    /**
     * 把"当前生效值"记进 {@link #applied}。刻意用 extract 重新读一遍而不是直接存入参：
     * 入参可能是别的实例写进库的残缺快照（缺键保持 yml 值），回滚时按当前实际生效值重放才准确。
     */
    private void snapshotCurrent(RuntimeConfigDomain domain) {
        applied.put(domain, RuntimeConfigSupport.extract(domain, properties));
    }

    private String writeJson(RuntimeConfigDomain domain, Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot == null ? Map.of() : snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("runtime config snapshot is not serialisable: domain=" + domain.key(), ex);
        }
    }

    private List<Map<String, Object>> describeInMemory(Map<String, RuntimeConfigEntity> rows) {
        return Arrays.stream(RuntimeConfigDomain.values()).map(domain -> {
            RuntimeConfigEntity row = rows.get(domain.key());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("domain", domain.key());
            item.put("source", fromDatabase.contains(domain) ? "database" : "yml");
            item.put("version", row == null || row.getVersion() == null ? 0L : row.getVersion());
            item.put("updatedAt", row == null || row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
            item.put("effective", RuntimeConfigSupport.extract(domain, properties));
            if (domain == RuntimeConfigDomain.SECURITY) {
                item.put("forcedOverridden", RuntimeConfigSupport.securityForceEnabled());
            }
            return item;
        }).toList();
    }
}
