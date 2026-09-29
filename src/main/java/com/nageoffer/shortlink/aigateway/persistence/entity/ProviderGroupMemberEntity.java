package com.nageoffer.shortlink.aigateway.persistence.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 通道组成员。
 * <p>
 * {@code model} 可选：跨厂商的组里同一个逻辑模型在不同 provider 上叫法不同，
 * 留空表示沿用路由解析出来的模型名。
 */
@Data
@Table("provider_group_member")
public class ProviderGroupMemberEntity {

    @Id
    private Long id;

    @Column("group_name")
    private String groupName;

    @Column("provider")
    private String provider;

    @Column("model")
    private String model;

    @Column("weight")
    private Integer weight;

    @Column("priority")
    private Integer priority;

    @Column("enabled")
    private Boolean enabled;
}
