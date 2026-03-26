package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@Table("tenant_api_key")
public class TenantApiKeyEntity {

    @Id
    private Long id;

    @Column("tenant_id")
    private String tenantId;

    @Column("app_id")
    private String appId;

    @Column("key_id")
    private String keyId;

    @Column("api_key")
    private String apiKey;

    @Column("enabled")
    private Boolean enabled;

    @Column("expires_at")
    private Instant expiresAt;
}
