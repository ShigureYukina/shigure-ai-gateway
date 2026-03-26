package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("tenant_model_mapping")
public class TenantModelMappingEntity {

    @Id
    private Long id;

    @Column("tenant_id")
    private String tenantId;

    @Column("request_model")
    private String requestModel;

    @Column("provider_model")
    private String providerModel;
}
