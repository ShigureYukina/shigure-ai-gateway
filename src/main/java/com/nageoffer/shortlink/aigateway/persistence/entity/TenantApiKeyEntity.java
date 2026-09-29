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

    /**
     * HMAC-SHA256(主密钥, 明文 Key) 的 64 位十六进制。
     * <p>
     * 加密开启后 {@link #apiKey} 每次写都是不同的密文（随机 IV），没法再做查找键与唯一键，
     * 所以查找走这一列。未配主密钥时为空，调用方必须回退到按 {@link #apiKey} 明文查找。
     */
    @Column("api_key_hash")
    private String apiKeyHash;

    @Column("enabled")
    private Boolean enabled;

    @Column("expires_at")
    private Instant expiresAt;
}
