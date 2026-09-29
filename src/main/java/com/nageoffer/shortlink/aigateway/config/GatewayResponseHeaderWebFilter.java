package com.nageoffer.shortlink.aigateway.config;

import com.nageoffer.shortlink.aigateway.governance.RateLimitHeaderService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * 响应头统一注入。
 * <p>
 * 两件事都必须在响应提交前完成，因此用 {@code beforeCommit} 而不是在过滤器里直接写：
 * <ul>
 *   <li>{@code X-Request-Id}：请求入口即归一化，保证网关日志、响应头、上游透传是同一个 ID；</li>
 *   <li>限流响应头：由服务层在结算后写入快照，这里取出并落到响应上。</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class GatewayResponseHeaderWebFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final RateLimitHeaderService rateLimitHeaderService;

    @Bean
    public WebFilter gatewayResponseHeadersWebFilter() {
        return (exchange, chain) -> {
            String requestId = resolveRequestId(exchange);
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .headers(headers -> headers.set(REQUEST_ID_HEADER, requestId))
                    .build();
            ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();
            mutatedExchange.getResponse().beforeCommit(() -> {
                HttpHeaders responseHeaders = mutatedExchange.getResponse().getHeaders();
                responseHeaders.set(REQUEST_ID_HEADER, requestId);
                for (Map.Entry<String, String> entry : rateLimitHeaderService.consume(requestId).entrySet()) {
                    if (!responseHeaders.containsKey(entry.getKey())) {
                        responseHeaders.set(entry.getKey(), entry.getValue());
                    }
                }
                return Mono.empty();
            });
            return chain.filter(mutatedExchange);
        };
    }

    private String resolveRequestId(ServerWebExchange exchange) {
        String requestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return requestId.trim();
    }
}
