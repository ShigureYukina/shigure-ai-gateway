package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 模型到通道组的绑定。
 * <p>
 * 一个模型只绑定一个组（表上唯一键保证），组决定这个模型的流量在哪些通道间分配。
 */
@Data
@Table("provider_group_binding")
public class ProviderGroupBindingEntity {

    @Id
    private Long id;

    @Column("model")
    private String model;

    @Column("group_name")
    private String groupName;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
