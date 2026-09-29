package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 通道组定义。
 * <p>
 * 组是"这些 provider 互为备份"的运行时声明，负载均衡策略挂在组上而非全局。
 */
@Data
@Table("provider_group")
public class ProviderGroupEntity {

    @Id
    private Long id;

    @Column("group_name")
    private String groupName;

    /**
     * 取值见 {@code LoadBalanceStrategy}，落库为枚举名。
     */
    @Column("strategy")
    private String strategy;

    @Column("enabled")
    private Boolean enabled;

    @Column("description")
    private String description;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
