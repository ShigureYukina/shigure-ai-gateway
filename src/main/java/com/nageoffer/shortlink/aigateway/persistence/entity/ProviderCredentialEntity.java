package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 上游 Provider 凭证。
 * <p>
 * {@code tenantId} 为 {@link #GLOBAL_TENANT_ID} 时表示平台级凭证；
 * 其余取值表示该租户的 BYOK 凭证，优先于平台级生效。
 */
@Data
@Table("provider_credential")
public class ProviderCredentialEntity {

    /**
     * 平台级凭证的伪租户标识。
     */
    public static final String GLOBAL_TENANT_ID = "*";

    @Id
    private Long id;

    @Column("tenant_id")
    private String tenantId;

    @Column("provider")
    private String provider;

    @Column("api_key")
    private String apiKey;

    @Column("auth_header")
    private String authHeader;

    @Column("auth_scheme")
    private String authScheme;

    @Column("extra_headers")
    private String extraHeaders;

    @Column("enabled")
    private Boolean enabled;
}
