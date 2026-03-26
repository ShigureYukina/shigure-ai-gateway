package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("tenant")
public class TenantEntity {

    @Id
    private Long id;

    @Column("tenant_id")
    private String tenantId;

    @Column("tenant_name")
    private String tenantName;

    @Column("tenant_status")
    private String tenantStatus;

    private String description;
}
