package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("tenant_app")
public class TenantAppEntity {

    @Id
    private Long id;

    @Column("tenant_id")
    private String tenantId;

    @Column("app_id")
    private String appId;

    @Column("app_name")
    private String appName;

    @Column("app_status")
    private String appStatus;

    private String description;
}
