package com.nageoffer.shortlink.aigateway.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiGatewayTracerTest {

    private Tracer tracer;
    private AiGatewayProperties properties;
    private AiGatewayTracer aiGatewayTracer;

    @BeforeEach
    void setUp() {
        tracer = mock(Tracer.class);
        properties = new AiGatewayProperties();
        aiGatewayTracer = new AiGatewayTracer(tracer, properties);
    }

    @Test
    void shouldReturnNullWhenTracingDisabled() {
        properties.getObservability().setTracingEnabled(false);

        Span result = aiGatewayTracer.startSpan("test-span");

        Assertions.assertNull(result);
        Mockito.verifyNoInteractions(tracer);
    }

    @Test
    void shouldCreateAndStartSpanWhenTracingEnabled() {
        properties.getObservability().setTracingEnabled(true);
        Span span = mock(Span.class);
        when(span.name(anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);
        doReturn(span).when(tracer).nextSpan();

        Span result = aiGatewayTracer.startSpan("test-span");

        Assertions.assertNotNull(result);
        verify(tracer).nextSpan();
        verify(span).name("test-span");
        verify(span).start();
    }

    @Test
    void shouldTagSpanWhenSpanNotNull() {
        Span span = mock(Span.class);

        aiGatewayTracer.tag(span, "request.id", "req-123");

        verify(span).tag("request.id", "req-123");
    }

    @Test
    void shouldNotTagWhenSpanNull() {
        aiGatewayTracer.tag(null, "request.id", "req-123");
    }

    @Test
    void shouldNotTagWhenValueNull() {
        Span span = mock(Span.class);

        aiGatewayTracer.tag(span, "request.id", null);

        verify(span, never()).tag(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void shouldEndSpanWithError() {
        Span span = mock(Span.class);
        RuntimeException error = new RuntimeException("test error");

        aiGatewayTracer.endWithError(span, error);

        verify(span).error(error);
        verify(span).end();
    }

    @Test
    void shouldNotEndWithErrorWhenSpanNull() {
        aiGatewayTracer.endWithError(null, new RuntimeException("error"));
    }

    @Test
    void shouldEndSpanNormally() {
        Span span = mock(Span.class);

        aiGatewayTracer.end(span);

        verify(span).end();
    }

    @Test
    void shouldNotEndWhenSpanNull() {
        aiGatewayTracer.end(null);
    }

    @Test
    void shouldHandleMultipleTagsOnSameSpan() {
        Span span = mock(Span.class);

        aiGatewayTracer.tag(span, "provider", "openai");
        aiGatewayTracer.tag(span, "model", "gpt-4o");
        aiGatewayTracer.tag(span, "tenant.id", "tenant-1");

        verify(span).tag("provider", "openai");
        verify(span).tag("model", "gpt-4o");
        verify(span).tag("tenant.id", "tenant-1");
    }

    @Test
    void shouldSupportFullSpanLifecycle() {
        properties.getObservability().setTracingEnabled(true);
        Span span = mock(Span.class);
        when(span.name(anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);
        doReturn(span).when(tracer).nextSpan();

        Span created = aiGatewayTracer.startSpan("chat.completion");
        Assertions.assertNotNull(created);

        aiGatewayTracer.tag(created, "provider", "openai");
        aiGatewayTracer.tag(created, "model", "gpt-4o");
        aiGatewayTracer.end(created);

        verify(span).tag("provider", "openai");
        verify(span).tag("model", "gpt-4o");
        verify(span).end();
    }

    @Test
    void shouldSupportFullSpanLifecycleWithError() {
        properties.getObservability().setTracingEnabled(true);
        Span span = mock(Span.class);
        when(span.name(anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);
        doReturn(span).when(tracer).nextSpan();

        Span created = aiGatewayTracer.startSpan("chat.completion");
        aiGatewayTracer.tag(created, "error.type", "upstream_timeout");
        aiGatewayTracer.endWithError(created, new RuntimeException("timeout"));

        verify(span).tag("error.type", "upstream_timeout");
        verify(span).error(Mockito.any(RuntimeException.class));
        verify(span).end();
    }
}
