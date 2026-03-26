package com.nageoffer.shortlink.aigateway.routing;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AiRoutingResult {

    private String provider;

    private String providerModel;

    private String upstreamUri;

    private AiRoutePolicy routePolicy;

    /**
     * 路由来源：default/header/alias/ab
     */
    private String routeSource;

    /**
     * A/B 命中（true 表示命中 B 组）
     */
    private Boolean abHit;

    /**
     * 失败回退候选（按顺序）
     */
    private List<FallbackRouteTarget> fallbackCandidates;

    @Data
    @Builder
    public static class FallbackRouteTarget {

        private String provider;

        private String providerModel;

        private String upstreamUri;

        private AiRoutePolicy routePolicy;
    }
}
