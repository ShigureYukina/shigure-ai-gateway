package com.nageoffer.shortlink.aigateway;

import com.nageoffer.shortlink.aigateway.controller.AiModelsController;
import com.nageoffer.shortlink.aigateway.crypto.MasterKey;
import com.nageoffer.shortlink.aigateway.crypto.SecretCipher;
import com.nageoffer.shortlink.aigateway.crypto.SecretHasher;
import com.nageoffer.shortlink.aigateway.governance.RateLimitHeaderService;
import com.nageoffer.shortlink.aigateway.governance.SemanticCacheService;
import com.nageoffer.shortlink.aigateway.governance.UpstreamCredentialService;
import com.nageoffer.shortlink.aigateway.probe.ChannelHealthReloader;
import com.nageoffer.shortlink.aigateway.probe.ChannelProbeScheduler;
import com.nageoffer.shortlink.aigateway.probe.ChannelProbeService;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigPoller;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigPublisher;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigService;
import com.nageoffer.shortlink.aigateway.security.PasswordEncoderSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * 上下文装配冒烟测试。
 * <p>
 * 单元测试用的是手工 new 出来的对象图，装配错误（缺 Bean、条件化 Bean 互斥失效、
 * 配置绑定失败）不会被它们发现，因此这里跑一次真实容器：
 * <ul>
 *   <li>语义缓存在开关两种取值下都必须能装配出一个实现；</li>
 *   <li>上游凭证与限流响应头这两条新链路必须真正成为 Bean；</li>
 *   <li>新增的 yml 配置项必须能绑定到 {@code AiGatewayProperties}。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "short-link.ai-gateway.tenant.persistence.enabled=false"
})
class AiGatewayApplicationContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void shouldWireGatewayCollaborators() {
        Assertions.assertEquals(1, applicationContext.getBeansOfType(SemanticCacheService.class).size());
        Assertions.assertNotNull(applicationContext.getBean(UpstreamCredentialService.class));
        Assertions.assertNotNull(applicationContext.getBean(RateLimitHeaderService.class));
        Assertions.assertNotNull(applicationContext.getBean(AiModelsController.class));
    }

    @Test
    void shouldBindProviderCredentialAndChatPathConfig() {
        com.nageoffer.shortlink.aigateway.config.AiGatewayProperties properties =
                applicationContext.getBean(com.nageoffer.shortlink.aigateway.config.AiGatewayProperties.class);

        Assertions.assertEquals("/v1/messages",
                properties.getUpstream().getProviderChatPath().get("claude"));
        Assertions.assertNotNull(properties.getUpstream().getProviderCredentials().get("openai"));
        Assertions.assertEquals("x-api-key",
                properties.getUpstream().getProviderCredentials().get("claude").getAuthHeader());
        Assertions.assertEquals(1000, properties.getCache().getSemanticIndexMaxEntries());
        Assertions.assertEquals(60L, properties.getRateLimit().getQuotaRetryAfterSeconds());
    }

    @Test
    void shouldWireRuntimeConfigCentre() {
        Assertions.assertNotNull(applicationContext.getBean(RuntimeConfigService.class));
        Assertions.assertNotNull(applicationContext.getBean(RuntimeConfigPoller.class));
        Assertions.assertNotNull(applicationContext.getBean(RuntimeConfigPublisher.class));

        com.nageoffer.shortlink.aigateway.config.AiGatewayProperties properties =
                applicationContext.getBean(com.nageoffer.shortlink.aigateway.config.AiGatewayProperties.class);

        // 这条同时兜住 @Scheduled 占位符前缀写错那类 bug：绑定失败时 Spring 会保留 Java 默认值，
        // 所以断言的必须是 yml 里显式配的值，而不是 PR 里刚写的那个默认值
        Assertions.assertEquals(Duration.ofSeconds(5), properties.getRuntimeConfig().getPollInterval());
        Assertions.assertEquals(Duration.ofSeconds(10), properties.getRuntimeConfig().getPollInitialDelay());
    }

    @Test
    void shouldWireSecretCryptoFromTheSameMasterKey() {
        Assertions.assertNotNull(applicationContext.getBean(MasterKey.class));
        Assertions.assertNotNull(applicationContext.getBean(SecretCipher.class));
        Assertions.assertNotNull(applicationContext.getBean(SecretHasher.class));
        Assertions.assertNotNull(applicationContext.getBean(PasswordEncoderSupport.class));

        com.nageoffer.shortlink.aigateway.config.AiGatewayProperties properties =
                applicationContext.getBean(com.nageoffer.shortlink.aigateway.config.AiGatewayProperties.class);

        // 测试环境没配 AI_GATEWAY_MASTER_KEY：加密必须是"关"而不是"坏"，否则本地写进去的 Key 就解不开了
        Assertions.assertFalse(applicationContext.getBean(SecretCipher.class).enabled());
        Assertions.assertFalse(applicationContext.getBean(SecretHasher.class).enabled());

        // dev 默认放行私网：本地把上游指向 http://localhost:11434 是正常用法
        Assertions.assertTrue(properties.getSecurity().getSsrf().isAllowPrivateAddresses());
    }

    @Test
    void shouldWireChannelProbeAndBindItsSchedule() {
        Assertions.assertNotNull(applicationContext.getBean(ChannelProbeService.class));
        Assertions.assertNotNull(applicationContext.getBean(ChannelProbeScheduler.class));

        // 版本号域名只有一个定义点：写方（探测服务）与读方（轮询器）引用同一个常量
        ChannelHealthReloader reloader = applicationContext.getBean(ChannelHealthReloader.class);
        Assertions.assertEquals(ChannelHealthReloader.KEY, reloader.key());

        com.nageoffer.shortlink.aigateway.config.AiGatewayProperties properties =
                applicationContext.getBean(com.nageoffer.shortlink.aigateway.config.AiGatewayProperties.class);

        // 与上面 runtime-config 同理：@Scheduled 的占位符前缀写错时绑定会静默失败，
        // 所以断言的必须是 yml 里显式配的值（PT5M/PT60S），不是 Java 默认值
        Assertions.assertEquals(Duration.ofMinutes(5), properties.getProbe().getInterval());
        Assertions.assertEquals(Duration.ofSeconds(60), properties.getProbe().getInitialDelay());
        Assertions.assertEquals(3, properties.getProbe().getFailureThreshold());
        Assertions.assertEquals(2, properties.getProbe().getRecoveryThreshold());
        Assertions.assertTrue(properties.getProbe().getChannelEnabled().isEmpty(),
                "channel-enabled 默认空集合：不能用 null 表示'没有静态禁用'");
    }

    /**
     * 两个 WebClient 是同一类型的 Bean，装配错误会以
     * "expected single matching bean but found 2" 的形式在启动期炸出来 ——
     * 单测都是手工传 WebClient，发现不了。
     */
    @Test
    void shouldWireBothWebClientsWithTheGatewayOneAsPrimary() {
        Assertions.assertEquals(2, applicationContext.getBeansOfType(WebClient.class).size());
        Assertions.assertNotNull(applicationContext.getBean("aiGatewayWebClient"));
        Assertions.assertNotNull(applicationContext.getBean("aiMetadataWebClient"));

        // @Primary 让没写 @Qualifier 的注入点（UpstreamCallExecutor）继续拿到数据面客户端
        Assertions.assertSame(applicationContext.getBean(WebClient.class),
                applicationContext.getBean("aiGatewayWebClient"));
    }
}
