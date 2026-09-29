package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 自动同步来的模型单价。
 * <p>
 * 与 {@link AiModelPriceEntity} 分开存：那张表是人手写的覆盖值，优先级永远高于这里的自动值，
 * 自动同步只负责"没人管过的模型"不至于没有价格。
 */
@Data
@Table("ai_model_price_sync")
public class AiModelPriceSyncEntity {

    @Id
    private Long id;

    private String model;

    @Column("input_per_1k")
    private Double inputPer1k;

    @Column("output_per_1k")
    private Double outputPer1k;

    @Column("source")
    private String source;

    @Column("synced_at")
    private LocalDateTime syncedAt;
}
