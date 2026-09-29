package com.nageoffer.shortlink.aigateway.sync;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.entity.AiModelPriceSyncEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.AiModelPriceSyncRepository;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import com.nageoffer.shortlink.aigateway.upstream.OutboundUrlValidator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型单价自动同步：从 models.dev 拉取价格快照。
 * <p>
 * 价格分三层，优先级从高到低：人工覆盖（ai_model_price / yml）-> 自动同步（本服务）。
 * 也就是说自动同步永远盖不掉人手写的价格，它只负责让"没人管过的模型"也有价格，
 * 否则成本统计与成本优先路由对长尾模型全是 0。
 */
@Slf4j
@Service
public class ModelPriceSyncService {

    private static final String SOURCE = "models.dev";

    private final AiGatewayProperties properties;

    private final WebClient metadataWebClient;

    private final UpstreamMetadataParser parser;

    private final TenantConfigQueryService tenantConfigQueryService;

    private final ObjectProvider<AiModelPriceSyncRepository> repository;

    /**
     * 显式构造器（不再用 {@code @RequiredArgsConstructor}）：需要给 WebClient 加
     * {@code @Qualifier} 指定元数据专用客户端（响应体上限 2MB，见 {@code WebClientConfiguration}）。
     * 参数个数与顺序不变，调用方零改动。
     */
    public ModelPriceSyncService(AiGatewayProperties properties,
                                 @Qualifier("aiMetadataWebClient") WebClient metadataWebClient,
                                 UpstreamMetadataParser parser,
                                 TenantConfigQueryService tenantConfigQueryService,
                                 ObjectProvider<AiModelPriceSyncRepository> repository) {
        this.properties = properties;
        this.metadataWebClient = metadataWebClient;
        this.parser = parser;
        this.tenantConfigQueryService = tenantConfigQueryService;
        this.repository = repository;
    }

    private volatile Instant lastSuccessAt;

    private volatile String lastError;

    private volatile int lastCount;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 重启后先用落库的快照预热，不必等下一次同步。
     */
    @PostConstruct
    void warmUp() {
        AiModelPriceSyncRepository priceRepository = repository.getIfAvailable();
        if (priceRepository == null) {
            return;
        }
        priceRepository.findAll()
                .collectList()
                .doOnNext(entities -> {
                    if (entities.isEmpty()) {
                        return;
                    }
                    Map<String, AiGatewayProperties.ModelPrice> prices = new LinkedHashMap<>();
                    entities.forEach(entity -> {
                        AiGatewayProperties.ModelPrice price = new AiGatewayProperties.ModelPrice();
                        price.setInputPer1k(entity.getInputPer1k());
                        price.setOutputPer1k(entity.getOutputPer1k());
                        prices.put(entity.getModel(), price);
                    });
                    tenantConfigQueryService.applySyncedPrices(prices);
                    this.lastCount = prices.size();
                })
                .subscribe(ignored -> {
                }, ex -> log.warn("failed to warm up synced prices: {}", ex.getMessage()));
    }

    /**
     * 立即同步一次。失败不抛给调用方，而是把原因记进状态里由管理面展示。
     */
    public Mono<Map<String, Object>> syncNow() {
        if (!properties.getSync().getPrice().isEnabled()) {
            return Mono.just(status("disabled"));
        }
        // 数据源地址只来自 yml（没有写入口），所以在发请求前再挡一道：
        // 即便配置被改成了内网地址或云元数据主机，也不会真的把请求发出去。
        // 按本方法"失败不抛给调用方"的合同，这里只把原因记进状态。
        String url = properties.getSync().getPrice().getUrl();
        try {
            OutboundUrlValidator.requireWellFormed(url);
        } catch (IllegalArgumentException ex) {
            this.lastError = "价格数据源地址不合法：" + ex.getMessage();
            log.warn("model price sync skipped: {}", lastError);
            return Mono.just(status("failed"));
        }
        if (!running.compareAndSet(false, true)) {
            return Mono.just(status("running"));
        }
        return metadataWebClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.getSync().getTimeout())
                .map(parser::parsePrices)
                .flatMap(prices -> apply(prices).thenReturn(prices))
                .doOnNext(prices -> {
                    this.lastCount = prices.size();
                    if (prices.isEmpty()) {
                        this.lastError = "本次未解析到任何价格，请检查数据源格式";
                        log.warn("price sync parsed nothing from {}", SOURCE);
                        return;
                    }
                    this.lastSuccessAt = Instant.now();
                    this.lastError = null;
                    log.info("model prices synced from {}: count={}", SOURCE, prices.size());
                })
                .doOnError(ex -> {
                    this.lastError = ex.getMessage();
                    log.warn("model price sync failed: {}", ex.getMessage());
                })
                .doFinally(signal -> running.set(false))
                .then(Mono.fromSupplier(() -> status(lastError == null ? "ok" : "degraded")))
                .onErrorResume(ex -> Mono.fromSupplier(() -> status("failed")));
    }

    public Map<String, Object> status() {
        return status(lastError == null ? "idle" : "failed");
    }

    private Mono<Void> apply(Map<String, AiGatewayProperties.ModelPrice> prices) {
        // 先热更新内存，保证没有数据库也能用
        tenantConfigQueryService.applySyncedPrices(prices);
        AiModelPriceSyncRepository priceRepository = repository.getIfAvailable();
        if (priceRepository == null || prices.isEmpty()) {
            return Mono.empty();
        }
        LocalDateTime syncedAt = LocalDateTime.now();
        List<AiModelPriceSyncEntity> entities = prices.entrySet().stream().map(entry -> {
            AiModelPriceSyncEntity entity = new AiModelPriceSyncEntity();
            entity.setModel(entry.getKey());
            entity.setInputPer1k(entry.getValue().getInputPer1k());
            entity.setOutputPer1k(entry.getValue().getOutputPer1k());
            entity.setSource(SOURCE);
            entity.setSyncedAt(syncedAt);
            return entity;
        }).toList();
        return priceRepository.deleteBySource(SOURCE)
                .thenMany(priceRepository.saveAll(entities))
                .onErrorResume(ex -> {
                    log.warn("failed to persist synced prices: {}", ex.getMessage());
                    return Flux.empty();
                })
                .then();
    }

    private Map<String, Object> status(String state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.getSync().getPrice().isEnabled());
        result.put("state", state);
        result.put("source", properties.getSync().getPrice().getUrl());
        result.put("modelCount", lastCount);
        result.put("lastSuccessAt", lastSuccessAt == null ? null : lastSuccessAt.toString());
        result.put("lastError", lastError);
        return result;
    }
}
