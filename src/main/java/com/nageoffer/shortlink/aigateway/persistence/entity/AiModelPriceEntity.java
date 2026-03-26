package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("ai_model_price")
public class AiModelPriceEntity {

    @Id
    private Long id;

    private String model;

    @Column("input_per_1k")
    private Double inputPer1k;

    @Column("output_per_1k")
    private Double outputPer1k;

    private Boolean enabled;
}
