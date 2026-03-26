package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("tenant_model_policy_allowed_model")
public class TenantModelAllowedEntity {

    @Id
    private Long id;

    @Column("tenant_id")
    private String tenantId;

    @Column("allowed_model")
    private String allowedModel;
}
