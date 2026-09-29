package com.nageoffer.shortlink.aigateway.sync;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.governance.UpstreamCredentialService;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderModelEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderModelRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型清单自动同步：向每个渠道的模型端点问一次"你有哪些模型"。
 * <p>
 * 只探测"能解析出平台级凭证"的渠道——没有 Key 的渠道去问必然 401，徒增噪音。
 * 另外，一次同步如果一个模型都没拿到（网络不通、上游改版），保留上一份快照而不是清空。
 * <p>
 * 真正发请求的是 {@link UpstreamModelProbe}：本类只负责"探测谁、怎么合并、什么时候保留旧快照"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelCatalogSyncService {

    private final AiGatewayProperties properties;

    private final UpstreamCredentialService upstreamCredentialService;

    private final UpstreamModelProbe modelProbe;

    private final ObjectProvider<ProviderModelRepository> repository;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Map<String, List<String>> catalog = Map.of();

    private volatile Instant lastSuccessAt;

    private volatile String lastError;

    /**
     * 重启后先用落库的清单预热，避免刚启动的第一个请求拿到空清单。
     */
    @PostConstruct
    void warmUpFromDatabase() {
        ProviderModelRepository modelRepository = repository.getIfAvailable();
        if (modelRepository == null) {
            return;
        }
        modelRepository.findAll()
                .collectList()
                .doOnNext(this::warmUp)
                .subscribe(ignored -> {
                }, ex -> log.warn("failed to warm up discovered models: {}", ex.getMessage()));
    }

    /**
     * 立即同步一次全部渠道的模型清单。
     */
    public Mono<Map<String, Object>> syncNow() {
        if (!properties.getSync().getModel().isEnabled()) {
            return Mono.just(status("disabled"));
        }
        if (!running.compareAndSet(false, true)) {
            return Mono.just(status("running"));
        }
        List<String> providers = properties.getUpstream().getProviderBaseUrl().keySet().stream()
                .filter(provider -> upstreamCredentialService.resolve(null, provider).isPresent())
                .toList();
        return Flux.fromIterable(providers)
                .concatMap(provider -> fetchModels(provider).map(models -> Map.entry(provider, models)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new)
                .flatMap(fetched -> apply(fetched).thenReturn(fetched))
                .doOnNext(fetched -> {
                    int total = fetched.values().stream().mapToInt(List::size).sum();
                    if (total == 0) {
                        this.lastError = "本次未发现任何模型，已保留上一份快照";
                        log.warn("model catalog sync returned nothing, keep previous snapshot");
                        return;
                    }
                    this.lastError = null;
                    this.lastSuccessAt = Instant.now();
                    log.info("model catalog synced: providers={}, models={}", fetched.size(), total);
                })
                .doOnError(ex -> {
                    this.lastError = ex.getMessage();
                    log.warn("model catalog sync failed: {}", ex.getMessage());
                })
                .doFinally(signal -> running.set(false))
                // 拿到空结果不是异常，但也不该报成 ok：上游可能改了返回结构
                .then(Mono.fromSupplier(() -> status(lastError == null ? "ok" : "degraded")))
                .onErrorResume(ex -> Mono.fromSupplier(() -> status("failed")));
    }

    /**
     * 某个渠道上已发现的模型；未同步过则为空。
     */
    public List<String> modelsOf(String provider) {
        return catalog.getOrDefault(provider, List.of());
    }

    /**
     * 全部已发现模型（跨渠道去重）。
     */
    public Set<String> allModels() {
        Set<String> models = new LinkedHashSet<>();
        catalog.values().forEach(models::addAll);
        return models;
    }

    public Map<String, Object> status() {
        return status(lastError == null ? "idle" : "failed");
    }

    private Mono<List<String>> fetchModels(String provider) {
        return modelProbe.probeWithStoredCredential(provider)
                .doOnNext(models -> log.debug("discovered models from {}: {}", provider, models.size()));
    }

    private Mono<Void> apply(Map<String, List<String>> fetched) {
        int total = fetched.values().stream().mapToInt(List::size).sum();
        if (total == 0) {
            return Mono.empty();
        }
        this.catalog = Map.copyOf(fetched);
        ProviderModelRepository modelRepository = repository.getIfAvailable();
        if (modelRepository == null) {
            return Mono.empty();
        }
        LocalDateTime syncedAt = LocalDateTime.now();
        List<ProviderModelEntity> entities = fetched.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(model -> {
                    ProviderModelEntity entity = new ProviderModelEntity();
                    entity.setProvider(entry.getKey());
                    entity.setModel(model);
                    entity.setSyncedAt(syncedAt);
                    return entity;
                }))
                .toList();
        return modelRepository.deleteAll()
                .thenMany(modelRepository.saveAll(entities))
                .onErrorResume(ex -> {
                    log.warn("failed to persist discovered models: {}", ex.getMessage());
                    return Flux.empty();
                })
                .then();
    }

    /**
     * 用落库的快照预热内存（重启后不必等下一次同步）。
     */
    public void warmUp(List<ProviderModelEntity> stored) {
        if (stored == null || stored.isEmpty()) {
            return;
        }
        Map<String, List<String>> restored = new LinkedHashMap<>();
        stored.forEach(entity -> restored.computeIfAbsent(entity.getProvider(), key -> new ArrayList<>())
                .add(entity.getModel()));
        this.catalog = Map.copyOf(restored);
    }

    private Map<String, Object> status(String state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.getSync().getModel().isEnabled());
        result.put("state", state);
        result.put("path", properties.getSync().getModel().getPath());
        result.put("providerCount", catalog.size());
        result.put("modelCount", allModels().size());
        result.put("providers", new LinkedHashMap<>(catalog));
        result.put("lastSuccessAt", lastSuccessAt == null ? null : lastSuccessAt.toString());
        result.put("lastError", lastError);
        return result;
    }
}
