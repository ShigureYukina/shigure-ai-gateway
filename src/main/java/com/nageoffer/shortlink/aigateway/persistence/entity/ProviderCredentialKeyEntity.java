package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 渠道 Key 池中的一把 Key。
 * <p>
 * 与 {@link ProviderCredentialEntity} 分开存：那张表描述"这个渠道怎么鉴权"
 * （header 名、scheme、附加头），这张表描述"这个渠道有哪几把钥匙"，
 * 于是同一渠道多 Key 轮换不需要改动既有的凭证结构。
 */
@Data
@Table("provider_credential_key")
public class ProviderCredentialKeyEntity {

    @Id
    private Long id;

    @Column("tenant_id")
    private String tenantId;

    @Column("provider")
    private String provider;

    @Column("key_id")
    private String keyId;

    @Column("api_key")
    private String apiKey;

    @Column("weight")
    private Integer weight;

    @Column("enabled")
    private Boolean enabled;
}
