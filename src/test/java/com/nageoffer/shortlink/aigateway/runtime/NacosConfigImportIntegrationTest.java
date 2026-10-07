package com.nageoffer.shortlink.aigateway.runtime;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Nacos Config 导入的端到端验证：CI 里先起 nacos 服务容器、往 dataId
 * {@code short-link-ai-gateway-it.yml} 写一条 {@code quota-retry-after-seconds: 99}
 * （本地 yml 的默认值是 60），本测试断言绑定结果确实来自 Nacos 而不是本地 yml。
 * <p>
 * 本地没有 Nacos：靠 {@code NACOS_CONFIG_IT=true} 环境变量门控，本地自动跳过。
 * 注意 {@code optional:nacos:} 前缀是"服务器不可达时跳过"，而不是"配置缺失时跳过"——
 * 服务器可达但 dataId 不存在时导入为空，断言就会失败，这正是要防的回归。
 */
@EnabledIfEnvironmentVariable(named = "NACOS_CONFIG_IT", matches = "true")
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.cloud.nacos.config.enabled=true",
        "spring.cloud.nacos.config.server-addr=127.0.0.1:8848",
        "spring.config.import=optional:nacos:short-link-ai-gateway-it.yml",
        "short-link.ai-gateway.tenant.persistence.enabled=false"
})
class NacosConfigImportIntegrationTest {

    @Autowired
    private AiGatewayProperties properties;

    @Test
    void shouldImportRateLimitQuotaFromNacosOverLocalYml() {
        Assertions.assertEquals(99L, properties.getRateLimit().getQuotaRetryAfterSeconds(),
                "该值必须来自 Nacos 的 short-link-ai-gateway-it.yml（本地 yml 是 60），"
                        + "若断言失败先确认 CI 的 Publish Nacos test config 步骤成功");
    }
}
