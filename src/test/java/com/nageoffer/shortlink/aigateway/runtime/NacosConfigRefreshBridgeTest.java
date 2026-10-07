package com.nageoffer.shortlink.aigateway.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.entity.RuntimeConfigEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.RuntimeConfigRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import reactor.core.publisher.Mono;

import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 刷新桥（{@link NacosConfigRefreshBridge}）+ {@code reapplyDatabaseOverrides} 的契约测试。
 * <p>
 * 场景：Nacos 触发整体重绑后，DB 运行时配置的覆盖被冲掉 —— 桥必须把它恢复回来，
 * 且只恢复落过库的域（没落库的域保持重绑结果，不能回滚到启动基线）。
 */
@ExtendWith(MockitoExtension.class)
class NacosConfigRefreshBridgeTest {

    @Mock
    private RuntimeConfigRepository repository;

    @Mock
    private ObjectProvider<RuntimeConfigRepository> repositoryProvider;

    private RuntimeConfigService newService(AiGatewayProperties properties) {
        when(repositoryProvider.getIfAvailable()).thenReturn(repository);
        RuntimeConfigVersions versions = mock(RuntimeConfigVersions.class);
        lenient().when(versions.nextToken()).thenReturn(Mono.just(1L));
        return new RuntimeConfigService(properties, repositoryProvider, new ObjectMapper(), versions);
    }

    private RuntimeConfigEntity row(String domain, String json) {
        RuntimeConfigEntity entity = new RuntimeConfigEntity();
        entity.setDomain(domain);
        entity.setConfigJson(json);
        entity.setVersion(1L);
        return entity;
    }

    @Test
    void shouldReapplyDatabaseOverrideAfterRebindClobbersIt() {
        AiGatewayProperties properties = new AiGatewayProperties();
        // 默认全部域没有落库行，再单独给 rate-limit 一行（后声明的具体桩覆盖 any()）
        when(repository.findByDomain(anyString())).thenReturn(Mono.empty());
        when(repository.findByDomain("rateLimit"))
                .thenReturn(Mono.just(row("rateLimit", "{\"enabled\":true,\"quotaRetryAfterSeconds\":99}")));

        RuntimeConfigService service = newService(properties);
        service.initialize();
        Assertions.assertTrue(properties.getRateLimit().isEnabled(), "启动期就该把 DB 的 enabled=true 应用进内存");

        // 模拟 Nacos 重绑：@ConfigurationPropertiesRebinder 会按 Environment（Nacos > yml）整体重置，
        // 内存里的 DB 覆盖随之消失
        properties.getRateLimit().setEnabled(false);
        properties.getRateLimit().setQuotaRetryAfterSeconds(60L);

        new NacosConfigRefreshBridge(service)
                .onApplicationEvent(new EnvironmentChangeEvent(Set.of("short-link.ai-gateway.rate-limit.enabled")));

        Assertions.assertTrue(properties.getRateLimit().isEnabled(), "刷新后 DB 覆盖必须被恢复（DB > Nacos > yml）");
        Assertions.assertEquals(99L, properties.getRateLimit().getQuotaRetryAfterSeconds());
    }

    @Test
    void shouldSkipReapplyWhenPersistenceUnavailable() {
        when(repositoryProvider.getIfAvailable()).thenReturn(null);
        AiGatewayProperties properties = new AiGatewayProperties();
        RuntimeConfigService service = new RuntimeConfigService(
                properties, repositoryProvider, new ObjectMapper(), mock(RuntimeConfigVersions.class));

        // 无 DB：桥应该是无害的空操作，而不是抛错或误触发其它路径
        new NacosConfigRefreshBridge(service)
                .onApplicationEvent(new EnvironmentChangeEvent(Set.of("anything")));
        Assertions.assertFalse(properties.getRateLimit().isEnabled());
    }
}
