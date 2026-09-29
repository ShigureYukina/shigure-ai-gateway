package com.nageoffer.shortlink.aigateway.persistence.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.entity.AiModelPriceEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.AiModelPriceRepository;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型价格域：单价的三层来源。
 * <p>
 * 取值顺序（只在这里定义一次，调用方不必自己拼 fallback）：
 * <ol>
 *   <li><b>人工覆盖</b>——{@code ai_model_price} 表，运维显式改过的价格，优先级最高；</li>
 *   <li><b>yml 手写</b>——{@code observability.model-price}，随配置走的静态价格；</li>
 *   <li><b>自动同步</b>——models.dev 拉回来的价格，优先级垫底：它只是"没人管过"时的兜底。</li>
 * </ol>
 * 关键语义：自动同步层<b>不随数据库快照清空</b>。价格是从 models.dev 来的、与本地库无关，
 * 库抖一下不该把同步结果一起抹掉（也因此 {@link #clear()} 只清人工层）。
 */
public class ModelPriceLookup {

    private final AiGatewayProperties properties;

    private final AiModelPriceRepository repository;

    /**
     * 人工覆盖层（数据库）。
     */
    private final Map<String, AiGatewayProperties.ModelPrice> manualCache = new ConcurrentHashMap<>();

    /**
     * 自动同步层（models.dev）。
     */
    private final Map<String, AiGatewayProperties.ModelPrice> syncedCache = new ConcurrentHashMap<>();

    public ModelPriceLookup(AiGatewayProperties properties, AiModelPriceRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    public boolean ready() {
        return repository != null;
    }

    public Mono<List<AiModelPriceEntity>> load() {
        if (!ready()) {
            return Mono.empty();
        }
        return repository.findAll().collectList();
    }

    /**
     * 整体替换人工覆盖层：只有 enabled 的行生效。
     */
    public void apply(List<AiModelPriceEntity> entities) {
        manualCache.clear();
        entities.stream()
                .filter(each -> Boolean.TRUE.equals(each.getEnabled()))
                .forEach(each -> manualCache.put(each.getModel(), toModelPrice(each)));
    }

    /**
     * 只清人工覆盖层，自动同步层保留。
     */
    public void clear() {
        manualCache.clear();
    }

    public int size() {
        return manualCache.size();
    }

    /**
     * 三层取值，见类注释。
     */
    public Optional<AiGatewayProperties.ModelPrice> find(String model) {
        if (DomainLookupSupport.snapshotEnabled(properties)) {
            AiGatewayProperties.ModelPrice manual = manualCache.get(model);
            if (manual != null) {
                return Optional.of(manual);
            }
        }
        AiGatewayProperties.ModelPrice fromYml = properties.getObservability().getModelPrice().get(model);
        if (fromYml != null) {
            return Optional.of(fromYml);
        }
        return Optional.ofNullable(syncedCache.get(model));
    }

    /**
     * 仅查自动同步层，供成本估算的诊断与测试使用：不受上面两层覆盖影响。
     */
    public Optional<AiGatewayProperties.ModelPrice> findSynced(String model) {
        return Optional.ofNullable(syncedCache.get(model));
    }

    /**
     * 用一次同步的结果整体替换自动同步层。
     */
    public void applySynced(Map<String, AiGatewayProperties.ModelPrice> prices) {
        syncedCache.clear();
        if (prices != null) {
            syncedCache.putAll(prices);
        }
    }

    public int syncedSize() {
        return syncedCache.size();
    }

    private AiGatewayProperties.ModelPrice toModelPrice(AiModelPriceEntity entity) {
        AiGatewayProperties.ModelPrice price = new AiGatewayProperties.ModelPrice();
        price.setInputPer1k(entity.getInputPer1k());
        price.setOutputPer1k(entity.getOutputPer1k());
        return price;
    }
}
