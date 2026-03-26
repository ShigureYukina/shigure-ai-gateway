package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("tenant_model_policy")
public class TenantModelPolicyEntity {

    @Id
    private Long id;

    @Column("tenant_id")
    private String tenantId;

    private Boolean enabled;

    @Column("default_model_alias")
    private String defaultModelAlias;

    @Column("default_model")
    private String defaultModel;
}
