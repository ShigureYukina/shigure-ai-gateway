package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.sync.ModelCatalogSyncService;
import com.nageoffer.shortlink.aigateway.sync.ModelPriceSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上游元数据同步：模型清单与价格。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/sync")
@Tag(name = "元数据同步", description = "模型清单与价格的自动同步状态与手动触发")
public class AiSyncController {

    private final ModelPriceSyncService modelPriceSyncService;

    private final ModelCatalogSyncService modelCatalogSyncService;

    @Operation(summary = "同步状态", description = "返回价格同步与模型发现的开关、上次成功时间与失败原因")
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("price", modelPriceSyncService.status());
        result.put("model", modelCatalogSyncService.status());
        return result;
    }

    @Operation(summary = "手动同步价格", description = "立即从 models.dev 拉取一次价格")
    @PostMapping("/prices")
    public Mono<Map<String, Object>> syncPrices() {
        return modelPriceSyncService.syncNow();
    }

    @Operation(summary = "手动发现模型", description = "立即向各渠道拉取一次模型清单")
    @PostMapping("/models")
    public Mono<Map<String, Object>> syncModels() {
        return modelCatalogSyncService.syncNow();
    }

    @Operation(summary = "手动同步全部", description = "依次执行价格同步与模型发现")
    @PostMapping("/all")
    public Mono<Map<String, Object>> syncAll() {
        return modelPriceSyncService.syncNow()
                .flatMap(priceStatus -> modelCatalogSyncService.syncNow().map(modelStatus -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("price", priceStatus);
                    result.put("model", modelStatus);
                    return result;
                }));
    }
}
