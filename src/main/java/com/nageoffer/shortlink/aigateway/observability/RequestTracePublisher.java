package com.nageoffer.shortlink.aigateway.observability;

import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 实时链路事件的发布入口。
 * <p>
 * 把事件装配从编排服务里拿出来，是为了让"可观测"这件事只有一个落点：编排逻辑只需声明
 * "现在是哪个阶段、什么结果"，不必知道事件有哪些字段、时间戳怎么算、租户字段从哪来。
 * <p>
 * 事件不参与任何业务判断，因此这里既不返回值也不抛错——看板断了不能影响用户请求。
 */
@Component
@RequiredArgsConstructor
public class RequestTracePublisher {

    private final AiRequestTraceBus requestTraceBus;

    /**
     * 发布一个链路阶段事件。
     *
     * @param start 请求开始时间，用于计算该阶段耗时；为 null 表示不计算（如路由前阶段）
     */
    public void publish(String requestId,
                        TenantContext tenantContext,
                        String stage,
                        String status,
                        String provider,
                        String model,
                        String detail,
                        Instant start,
                        Boolean stream) {
        requestTraceBus.publish(AiRequestTraceEvent.builder()
                .requestId(requestId)
                .tenantId(tenantContext == null ? null : tenantContext.tenantId())
                .appId(tenantContext == null ? null : tenantContext.appId())
                .stage(stage)
                .status(status)
                .provider(provider)
                .model(model)
                .detail(detail)
                .stream(stream)
                .latencyMillis(start == null ? null : Duration.between(start, Instant.now()).toMillis())
                .timestamp(System.currentTimeMillis())
                .build());
    }
}
