package com.nageoffer.shortlink.aigateway.config;

import io.micrometer.tracing.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TracingConfiguration {

    @Bean
    public AiGatewayTracer aiGatewayTracer(Tracer tracer, AiGatewayProperties properties) {
        return new AiGatewayTracer(tracer, properties);
    }
}
