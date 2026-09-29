package com.nageoffer.shortlink.aigateway.service;

import com.nageoffer.shortlink.aigateway.routing.AiRoutePolicy;
import com.nageoffer.shortlink.aigateway.routing.AiRoutingResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次请求可尝试的一个路由目标：主路由，或某个回退候选。
 * <p>
 * 之所以把它从编排服务里提出来，是因为"尝试哪个目标"同时被编排侧（决定尝试序列、记录实际服务方）
 * 与执行侧（真正发请求）使用，只有一处定义才不会出现两边理解不一致。
 */
public record RouteTarget(String provider, String providerModel, String upstreamUri, AiRoutePolicy routePolicy) {

    /**
     * 把路由结果展开为尝试序列：主路由在首位，其后是回退候选。
     */
    public static List<RouteTarget> from(AiRoutingResult routing) {
        List<RouteTarget> targets = new ArrayList<>();
        targets.add(new RouteTarget(routing.getProvider(), routing.getProviderModel(),
                routing.getUpstreamUri(), routing.getRoutePolicy()));
        if (routing.getFallbackCandidates() != null) {
            for (AiRoutingResult.FallbackRouteTarget fallbackCandidate : routing.getFallbackCandidates()) {
                targets.add(new RouteTarget(
                        fallbackCandidate.getProvider(),
                        fallbackCandidate.getProviderModel(),
                        fallbackCandidate.getUpstreamUri(),
                        fallbackCandidate.getRoutePolicy()
                ));
            }
        }
        return targets;
    }
}
