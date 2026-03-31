package com.nageoffer.shortlink.aigateway.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;

/**
 * 网关链路追踪辅助类。
 * <p>
 * 封装 Micrometer Tracing 的 span 创建/关闭逻辑，
 * 在 tracingEnabled=false 时退化为 noop。
 */
@RequiredArgsConstructor
public class AiGatewayTracer {

    private final Tracer tracer;

    private final AiGatewayProperties properties;

    /**
     * 创建并启动一个 span。
     */
    public Span startSpan(String name) {
        if (!properties.getObservability().isTracingEnabled()) {
            return null;
        }
        return tracer.nextSpan().name(name).start();
    }

    /**
     * 为 span 添加标签。
     */
    public void tag(Span span, String key, String value) {
        if (span != null && value != null) {
            span.tag(key, value);
        }
    }

    /**
     * 标记 span 为错误并关闭。
     */
    public void endWithError(Span span, Throwable error) {
        if (span != null) {
            span.error(error);
            span.end();
        }
    }

    /**
     * 正常关闭 span。
     */
    public void end(Span span) {
        if (span != null) {
            span.end();
        }
    }
}
