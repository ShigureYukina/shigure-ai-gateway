package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class QuotaKeyGeneratorTest {

    @Test
    void shouldBuildDimensionKey() {
        AiGatewayProperties properties = new AiGatewayProperties();
        QuotaKeyGenerator keyGenerator = new QuotaKeyGenerator(properties);

        HttpHeaders headers = new HttpHeaders();
        headers.add("userId", "u1");
        headers.add("X-Forwarded-For", "1.1.1.1,2.2.2.2");
        headers.add("X-Consumer", "c1");

        String key = keyGenerator.build(new TenantContext("tenant-a", "app-a", "key-a"), headers, "openai", "gpt-4o-mini");
        Assertions.assertTrue(key.contains("provider=openai"));
        Assertions.assertTrue(key.contains("model=gpt-4o-mini"));
        Assertions.assertTrue(key.contains("tenantId=tenant-a"));
        Assertions.assertTrue(key.contains("appId=app-a"));
        Assertions.assertTrue(key.contains("keyId=key-a"));
        Assertions.assertTrue(key.contains("userId=u1"));
        Assertions.assertTrue(key.contains("ip=1.1.1.1"));
        Assertions.assertTrue(key.contains("consumer=c1"));
    }
}
