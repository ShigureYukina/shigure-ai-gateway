package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("tenant_quota_policy")
public class TenantQuotaPolicyEntity {

    @Id
    private Long id;

    @Column("tenant_id")
    private String tenantId;

    private Boolean enabled;

    @Column("token_quota_per_minute")
    private Long tokenQuotaPerMinute;

    @Column("token_quota_per_day")
    private Long tokenQuotaPerDay;

    @Column("token_quota_per_month")
    private Long tokenQuotaPerMonth;
}
