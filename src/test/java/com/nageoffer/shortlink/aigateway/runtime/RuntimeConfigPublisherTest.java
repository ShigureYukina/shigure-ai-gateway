package com.nageoffer.shortlink.aigateway.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.persistence.entity.RuntimeConfigEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.RuntimeConfigRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写入口最要紧的一条：<b>写库失败必须把内存回滚掉</b>。
 * <p>
 * 不回滚的话，控制台收到 400、以为没生效，而本实例其实已经按新值在跑了 —— 这是最难排查的一类不一致。
 * 第二个断言同样关键：回滚的目标是"上一次成功生效的值"，不是启动时的 yml 基线，否则会顺手把
 * 运维上一步的配置也撤销掉。
 */
class RuntimeConfigPublisherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReportNotPersistedWhenNoRepositoryIsRegistered() {
        RuntimeConfigPublisher publisher = RuntimeConfigPublisher.noPersistence();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", true);
        Map<String, Object> response = publisher.save(RuntimeConfigDomain.CACHE, view).block();

        Assertions.assertNotNull(response);
        Assertions.assertEquals("cache", response.get("domain"));
        Assertions.assertEquals(Boolean.FALSE, response.get("persisted"));
        Assertions.assertEquals(0L, response.get("version"));
        Assertions.assertEquals(true, response.get("enabled"), "原有 view 字段要保留");
        Assertions.assertDoesNotThrow(() -> publisher.reset(RuntimeConfigDomain.CACHE).block());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPersistEffectiveValuesAsFlatJson() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getCache().setEnabled(true);
        properties.getCache().setTtl(Duration.ofSeconds(75));
        RuntimeConfigRepository repository = repository();
        ArgumentCaptor<RuntimeConfigEntity> saved = ArgumentCaptor.forClass(RuntimeConfigEntity.class);
        Mockito.when(repository.save(saved.capture())).thenReturn(Mono.just(new RuntimeConfigEntity()));
        RuntimeConfigVersions versions = versions(3L);

        RuntimeConfigPublisher publisher = publisher(properties, repository, versions);

        Map<String, Object> response = publisher.save(RuntimeConfigDomain.CACHE,
                Map.of("enabled", true, "ttlSeconds", 75L)).block();

        Assertions.assertNotNull(response);
        Assertions.assertEquals(Boolean.TRUE, response.get("persisted"));
        Assertions.assertEquals(3L, response.get("version"));

        Map<String, Object> persisted = read(saved.getValue().getConfigJson());
        Assertions.assertEquals(true, persisted.get("enabled"));
        Assertions.assertEquals(75, persisted.get("ttlSeconds"), "Duration 必须落成秒数，否则单测里的 ObjectMapper 解不开");
        Mockito.verify(versions).publish("cache", 3L);
    }

    @Test
    void shouldRollBackToLastSuccessfulValueWhenPersistFails() {
        AiGatewayProperties properties = new AiGatewayProperties();
        RuntimeConfigRepository repository = repository();
        Mockito.when(repository.save(Mockito.any())).thenReturn(Mono.just(new RuntimeConfigEntity()));
        RuntimeConfigVersions versions = versions(1L);
        RuntimeConfigPublisher publisher = publisher(properties, repository, versions);

        // 第一次写入成功，记下当时的生效值
        Duration ttlBeforeSecondWrite = properties.getCache().getTtl();
        properties.getCache().setEnabled(true);
        Assertions.assertEquals(Boolean.TRUE,
                publisher.save(RuntimeConfigDomain.CACHE, Map.of("enabled", true)).block().get("persisted"));

        // 第二次写入：库开始报错，且这次改的是另一个字段
        Mockito.when(repository.save(Mockito.any())).thenReturn(Mono.error(new IllegalStateException("database down")));
        properties.getCache().setTtl(Duration.ofSeconds(999));

        AiGatewayClientException ex = Assertions.assertThrows(AiGatewayClientException.class,
                () -> publisher.save(RuntimeConfigDomain.CACHE, Map.of("ttlSeconds", 999L)).block());

        Assertions.assertEquals(AiGatewayErrorCode.BAD_REQUEST, ex.getErrorCode());
        // 回滚到上一次成功生效的值：enabled 保持 true（而不是回落到 yml 的 false），ttl 回到写入前的值
        Assertions.assertTrue(properties.getCache().isEnabled());
        Assertions.assertEquals(ttlBeforeSecondWrite, properties.getCache().getTtl());
    }

    @Test
    void shouldRollBackToYmlBaselineWhenTheVeryFirstWriteFails() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getCache().setEnabled(false);
        RuntimeConfigRepository repository = repository();
        Mockito.when(repository.save(Mockito.any())).thenReturn(Mono.error(new IllegalStateException("database down")));
        RuntimeConfigPublisher publisher = publisher(properties, repository, versions(1L));

        properties.getCache().setEnabled(true);

        Assertions.assertThrows(AiGatewayClientException.class,
                () -> publisher.save(RuntimeConfigDomain.CACHE, Map.of("enabled", true)).block());

        Assertions.assertFalse(properties.getCache().isEnabled(), "首次写入失败应回到 yml 基线");
    }

    @Test
    void shouldResetDomainBackToYmlValues() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getCache().setEnabled(false);
        RuntimeConfigRepository repository = repository();
        RuntimeConfigEntity row = new RuntimeConfigEntity();
        row.setDomain("cache");
        row.setConfigJson("{\"enabled\":true}");
        row.setVersion(2L);
        Mockito.when(repository.findByDomain("cache")).thenReturn(Mono.just(row));
        Mockito.when(repository.save(Mockito.any())).thenReturn(Mono.just(row));
        Mockito.when(repository.deleteById("cache")).thenReturn(Mono.empty());
        RuntimeConfigPublisher publisher = publisher(properties, repository, versions(9L));

        Assertions.assertNotNull(publisher.save(RuntimeConfigDomain.CACHE, Map.of()).block());
        Assertions.assertTrue(properties.getCache().isEnabled());

        publisher.reset(RuntimeConfigDomain.CACHE).block();

        Mockito.verify(repository).deleteById("cache");
        Assertions.assertFalse(properties.getCache().isEnabled());
    }

    private RuntimeConfigPublisher publisher(AiGatewayProperties properties,
                                             RuntimeConfigRepository repository,
                                             RuntimeConfigVersions versions) {
        RuntimeConfigService service = service(properties, repository, versions);
        service.initialize();
        return new RuntimeConfigPublisher(properties, service);
    }

    @SuppressWarnings("unchecked")
    private RuntimeConfigService service(AiGatewayProperties properties,
                                         RuntimeConfigRepository repository,
                                         RuntimeConfigVersions versions) {
        ObjectProvider<RuntimeConfigRepository> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(repository);
        return new RuntimeConfigService(properties, provider, objectMapper, versions);
    }

    private RuntimeConfigVersions versions(long token) {
        RuntimeConfigVersions versions = Mockito.mock(RuntimeConfigVersions.class);
        Mockito.when(versions.nextToken()).thenReturn(Mono.just(token));
        Mockito.when(versions.publish(Mockito.anyString(), Mockito.anyLong())).thenReturn(Mono.empty());
        return versions;
    }

    private RuntimeConfigRepository repository() {
        RuntimeConfigRepository repository = Mockito.mock(RuntimeConfigRepository.class);
        Mockito.when(repository.findByDomain(Mockito.anyString())).thenReturn(Mono.empty());
        return repository;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
