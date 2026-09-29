package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 从渠道发现到的可用模型。
 */
@Data
@Table("provider_model")
public class ProviderModelEntity {

    @Id
    private Long id;

    @Column("provider")
    private String provider;

    @Column("model")
    private String model;

    @Column("synced_at")
    private LocalDateTime syncedAt;
}
