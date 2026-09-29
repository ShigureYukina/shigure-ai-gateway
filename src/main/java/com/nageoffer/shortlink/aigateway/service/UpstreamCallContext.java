package com.nageoffer.shortlink.aigateway.service;

import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.List;

/**
 * 一次请求在上游调用阶段不变的那组上下文。
 * <p>
 * 回退循环此前要把 {@code routeTargets / request / forwardHeaders / requestId / tenantContext / start}
 * 逐个往下传，递归一层就多一层长参数列表；这些值在一次请求内是恒定的，收进一个记录后
 * 递归与下游方法都只需传它自己，尝试序号单独作为参数。
 */
public record UpstreamCallContext(
        List<RouteTarget> routeTargets,
        AiChatCompletionReqDTO request,
        HttpHeaders forwardHeaders,
        String requestId,
        TenantContext tenantContext,
        Instant start) {

    public RouteTarget target(int index) {
        return routeTargets.get(index);
    }

    /**
     * 是否还有下一个可回退的目标。
     */
    public boolean hasNext(int index) {
        return index < routeTargets.size() - 1;
    }
}
