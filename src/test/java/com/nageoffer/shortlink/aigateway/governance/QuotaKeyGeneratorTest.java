package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetSocketAddress;
import java.util.List;

class QuotaKeyGeneratorTest {

    @Test
    void shouldBuildKeyFromServerSideIdentity() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getRateLimit().setKeyDimensions(List.of("userId", "ip", "consumer"));
        QuotaKeyGenerator keyGenerator = new QuotaKeyGenerator(properties);

        // 客户端头里的 userId / X-Forwarded-For / X-Consumer 一律不可信：
        // 配额身份只能来自认证上下文与连接远端地址，否则轮换请求头即可绕过配额
        ServerHttpRequest request = MockServerHttpRequest.post("/v1/chat/completions")
                .header("userId", "spoofed-u1")
                .header("X-Forwarded-For", "1.1.1.1,2.2.2.2")
                .header("X-Consumer", "spoofed-c1")
                .remoteAddress(new InetSocketAddress("10.0.0.7", 50000))
                .build();

        String key = keyGenerator.build(new TenantContext("tenant-a", "app-a", "key-a"), request, "openai", "gpt-4o-mini");
        Assertions.assertTrue(key.contains("provider=openai"));
        Assertions.assertTrue(key.contains("model=gpt-4o-mini"));
        Assertions.assertTrue(key.contains("tenantId=tenant-a"));
        Assertions.assertTrue(key.contains("appId=app-a"));
        Assertions.assertTrue(key.contains("keyId=key-a"));
        Assertions.assertTrue(key.contains("userId=key-a"));
        Assertions.assertTrue(key.contains("ip=10.0.0.7"));
        Assertions.assertTrue(key.contains("consumer=app-a"));
        Assertions.assertFalse(key.contains("spoofed"));
        Assertions.assertFalse(key.contains("1.1.1.1"));
    }

    @Test
    void shouldFallBackToGlobalIdentityWhenTenantMissing() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getRateLimit().setKeyDimensions(List.of("userId", "ip"));
        QuotaKeyGenerator keyGenerator = new QuotaKeyGenerator(properties);

        ServerHttpRequest request = MockServerHttpRequest.get("/v1/chat/completions")
                .remoteAddress(new InetSocketAddress("192.168.1.5", 40000))
                .build();

        String key = keyGenerator.build(null, request, "openai", "gpt-4o-mini");
        Assertions.assertTrue(key.contains("userId=" + properties.getTenant().getDefaultKeyId()));
        Assertions.assertTrue(key.contains("ip=192.168.1.5"));
    }

    @Test
    void shouldKeepExplicitConfiguredHeaderDimension() {
        // 自定义维度是部署方显式配置的信任决定（如可信代理统一覆写的 X-Real-IP），仍从头读取
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getRateLimit().setKeyDimensions(List.of("ip", "X-Real-IP"));
        QuotaKeyGenerator keyGenerator = new QuotaKeyGenerator(properties);

        ServerHttpRequest request = MockServerHttpRequest.get("/v1/chat/completions")
                .header("X-Real-IP", "203.0.113.9")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 40000))
                .build();

        String key = keyGenerator.build(new TenantContext("tenant-a", "app-a", "key-a"), request, "openai", "gpt-4o-mini");
        Assertions.assertTrue(key.contains("ip=10.0.0.1"));
        Assertions.assertTrue(key.contains("X-Real-IP=203.0.113.9"));
    }
}
