package com.nageoffer.shortlink.aigateway.config;

import com.nageoffer.shortlink.aigateway.audit.AuditLogService;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.security.ConsoleAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.WebFilter;

@Configuration
@RequiredArgsConstructor
public class ConsoleSecurityAuditWebFilter {

    private final AiGatewayProperties properties;

    private final ConsoleAuthService consoleAuthService;

    private final AuditLogService auditLogService;

    @Bean
    public WebFilter consoleSecurityWebFilter() {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getPath().value();
            if (!path.startsWith("/v1/") || "/v1/chat/completions".equals(path) || "/v1/security/login".equals(path)) {
                return chain.filter(exchange);
            }
            if (!properties.getSecurity().isEnabled()) {
                return chain.filter(exchange);
            }

            ConsoleAuthService.AuthPrincipal principal = consoleAuthService.authenticate(exchange.getRequest().getHeaders());
            if (isWriteMethod(exchange.getRequest().getMethod()) && !"/v1/security/logout".equals(path)) {
                consoleAuthService.assertWriteAllowed(principal);
            }

            return chain.filter(exchange)
                    .doOnSuccess(unused -> auditLogService.record(
                            principal.username(),
                            principal.role(),
                            "HTTP_" + exchange.getRequest().getMethod(),
                            path,
                            true,
                            "status=" + (exchange.getResponse().getStatusCode() == null ? 200 : exchange.getResponse().getStatusCode().value())
                    ))
                    .doOnError(ex -> {
                        String detail = ex instanceof AiGatewayClientException clientException
                                ? clientException.getMessage()
                                : ex.getMessage();
                        auditLogService.record(principal.username(), principal.role(), "HTTP_" + exchange.getRequest().getMethod(), path, false, detail);
                    });
        };
    }

    private boolean isWriteMethod(HttpMethod httpMethod) {
        return httpMethod == HttpMethod.POST || httpMethod == HttpMethod.PUT || httpMethod == HttpMethod.PATCH || httpMethod == HttpMethod.DELETE;
    }
}
