package com.nageoffer.shortlink.aigateway.dto.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 网关规范化聊天请求。
 * <p>
 * 适配器只依赖这一层：{@code messages} 已归一为通用 Map 结构，
 * {@code extra} 承载未显式建模的透传字段。
 */
@Data
@Builder
public class AiCanonicalChatRequest {

    private String provider;

    private String clientModel;

    private String providerModel;

    private Boolean stream;

    private Double temperature;

    private Integer maxTokens;

    private List<Map<String, Object>> messages;

    private Map<String, Object> metadata;

    /**
     * 客户端原始扩展字段，由适配器按上游协议决定合并或裁剪。
     */
    private Map<String, Object> extra;
}
