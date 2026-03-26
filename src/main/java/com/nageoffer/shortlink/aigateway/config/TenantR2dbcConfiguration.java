package com.nageoffer.shortlink.aigateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@ConditionalOnProperty(prefix = "short-link.ai-gateway.tenant.persistence", name = "enabled", havingValue = "true")
@EnableR2dbcRepositories(basePackages = "com.nageoffer.shortlink.aigateway.persistence.repository")
public class TenantR2dbcConfiguration {
}
