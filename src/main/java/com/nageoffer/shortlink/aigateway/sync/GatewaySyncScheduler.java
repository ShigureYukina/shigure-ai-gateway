package com.nageoffer.shortlink.aigateway.sync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 上游元数据同步的定时入口。
 * <p>
 * 间隔与首次延迟直接从配置读取，因此必须写成 ISO-8601（如 {@code PT12H}）——
 * {@code @Scheduled} 只认 ISO-8601 或纯毫秒数，写成 {@code 12h} 会在启动时直接报错。
 * 首次延迟给得比较长，避免应用刚起来就抢网络资源，也让测试不会跑到同步任务。
 * <p>
 * 占位符必须写全前缀（{@code short-link.ai-gateway.*}）：这里曾是 {@code ai-gateway.*}，
 * 与 yml 的键对不上，导致改 yml 的 interval 静默不生效、一直用注解里的默认值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewaySyncScheduler {

    private final ModelPriceSyncService modelPriceSyncService;

    private final ModelCatalogSyncService modelCatalogSyncService;

    @Scheduled(initialDelayString = "${short-link.ai-gateway.sync.price.initial-delay:PT60S}",
            fixedDelayString = "${short-link.ai-gateway.sync.price.interval:PT12H}")
    public void syncModelPrices() {
        modelPriceSyncService.syncNow()
                .subscribe(status -> log.debug("scheduled model price sync: {}", status),
                        ex -> log.warn("scheduled model price sync failed: {}", ex.getMessage()));
    }

    @Scheduled(initialDelayString = "${short-link.ai-gateway.sync.model.initial-delay:PT90S}",
            fixedDelayString = "${short-link.ai-gateway.sync.model.interval:PT6H}")
    public void syncModelCatalog() {
        modelCatalogSyncService.syncNow()
                .subscribe(status -> log.debug("scheduled model catalog sync: {}", status),
                        ex -> log.warn("scheduled model catalog sync failed: {}", ex.getMessage()));
    }
}
