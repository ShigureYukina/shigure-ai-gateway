package com.nageoffer.shortlink.aigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 场景下 gateway-console 的 SPA 回退路由。
 *
 * 规则：
 * - 仅处理 /gateway-console 与其子路径
 * - 含文件扩展名的静态资源路径不回退（如 .js/.css/.png）
 * - 其余路径统一重写为 /gateway-console/index.html
 */
@Configuration
public class GatewayConsoleSpaFallbackFilter {

    @Bean
    public WebFilter gatewayConsoleSpaFallbackWebFilter() {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getPath().value();

            if (!path.startsWith("/gateway-console")) {
                return chain.filter(exchange);
            }

            if ("/gateway-console".equals(path)
                    || "/gateway-console/".equals(path)) {
                return forwardToIndex(exchange, chain);
            }

            String relativePath = path.substring("/gateway-console/".length());
            if (relativePath.contains(".")) {
                return chain.filter(exchange);
            }

            return forwardToIndex(exchange, chain);
        };
    }

    private Mono<Void> forwardToIndex(org.springframework.web.server.ServerWebExchange exchange,
                                       WebFilterChain chain) {
        org.springframework.http.server.reactive.ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .path("/gateway-console/index.html")
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }
}
