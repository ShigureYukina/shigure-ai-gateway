package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 运行时配置的一行 = 一个域的快照。
 * <p>
 * 与租户配置、通道组一样走"DB 为真源、内存快照在消费端"的路子：
 * 这里只负责存，{@code runtime.RuntimeConfigService} 负责把 JSON 应用回 {@code AiGatewayProperties}。
 */
@Data
@Table("runtime_config")
public class RuntimeConfigEntity {

    /**
     * 配置域名，见 {@code runtime.RuntimeConfigDomain}。
     */
    @Id
    @Column("domain")
    private String domain;

    @Column("config_json")
    private String configJson;

    @Column("version")
    private Long version;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
