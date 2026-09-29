package com.nageoffer.shortlink.aigateway.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.entity.RuntimeConfigEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.RuntimeConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * "DB 为真源、yml 为基线"这条主线的行为断言。
 * <p>
 * 这里刻意不断言"内存里存了什么"（那是实现细节），只断言<b>可观察的取值</b>：
 * 属性对象上读到的值、写到库里的 JSON、以及"DB 挂了不许把值清空"。
 */
class RuntimeConfigServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearForceFlag() {
        System.clearProperty(RuntimeConfigSupport.SECURITY_FORCE_ENABLED_KEY);
    }

    @Test
    void shouldOverlayDatabaseSnapshotOnTopOfYmlValues() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getCache().setEnabled(false);
        RuntimeConfigRepository repository = repository();
        Mockito.when(repository.findByDomain("cache"))
                .thenReturn(Mono.just(row("cache", Map.of("enabled", true, "ttlSeconds", 30L), 7L)));

        RuntimeConfigService service = service(properties, repository);
        service.initialize();

        Assertions.assertTrue(properties.getCache().isEnabled(), "库里有 enabled=true 就该压过 yml 的 false");
        Assertions.assertEquals(Duration.ofSeconds(30), properties.getCache().getTtl());
    }

    @Test
    void shouldKeepYmlValuesForDomainsWithoutRow() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getRateLimit().setDefaultTokenQuotaPerMinute(4321L);
        RuntimeConfigRepository repository = repository();

        RuntimeConfigService service = service(properties, repository);
        service.initialize();

        Assertions.assertEquals(4321L, properties.getRateLimit().getDefaultTokenQuotaPerMinute());
    }

    @Test
    void shouldWarnOnlyWhenStartupLoadFails() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getCache().setSemanticIndexMaxEntries(777);
        RuntimeConfigRepository repository = repository();
        Mockito.when(repository.findByDomain(Mockito.anyString()))
                .thenReturn(Mono.error(new IllegalStateException("database down")));

        RuntimeConfigService service = service(properties, repository);

        // DB 不可达不能让应用起不来，也不能把 yml 值改坏
        Assertions.assertDoesNotThrow(service::initialize);
        Assertions.assertEquals(777, properties.getCache().getSemanticIndexMaxEntries());
        Assertions.assertTrue(service.persistable());
    }

    @Test
    void shouldKeepCurrentValuesWhenReloadFails() {
        AiGatewayProperties properties = new AiGatewayProperties();
        RuntimeConfigRepository repository = repository();
        Mockito.when(repository.findByDomain("cache"))
                .thenReturn(Mono.just(row("cache", Map.of("ttlSeconds", 120L), 3L)));

        RuntimeConfigService service = service(properties, repository);
        service.initialize();
        Assertions.assertEquals(Duration.ofSeconds(120), properties.getCache().getTtl());

        Mockito.when(repository.findByDomain("cache"))
                .thenReturn(Mono.error(new IllegalStateException("database down")));
        service.reload(RuntimeConfigDomain.CACHE).block();

        // 读失败 → 保持现有内存值，而不是回落到 yml 基线
        Assertions.assertEquals(Duration.ofSeconds(120), properties.getCache().getTtl());
    }

    @Test
    void shouldRestoreBaselineAfterRowIsDeleted() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getCache().setEnabled(false);
        properties.getCache().setTtl(Duration.ofSeconds(60));
        RuntimeConfigRepository repository = repository();
        Mockito.when(repository.findByDomain("cache"))
                .thenReturn(Mono.just(row("cache", Map.of("enabled", true, "ttlSeconds", 600L), 5L)));

        RuntimeConfigService service = service(properties, repository);
        service.initialize();
        Assertions.assertTrue(properties.getCache().isEnabled());

        Mockito.when(repository.findByDomain("cache")).thenReturn(Mono.empty());
        service.reload(RuntimeConfigDomain.CACHE).block();

        Assertions.assertFalse(properties.getCache().isEnabled());
        Assertions.assertEquals(Duration.ofSeconds(60), properties.getCache().getTtl());
    }

    @Test
    void shouldDegradeToMemoryOnlyWhenRepositoryAbsent() {
        AiGatewayProperties properties = new AiGatewayProperties();
        RuntimeConfigService service = service(properties, null);

        service.initialize();

        Assertions.assertFalse(service.persistable());
        Assertions.assertNull(service.persist(RuntimeConfigDomain.CACHE, Map.of("enabled", true)).block());

        // 不落库的实例，删除等于"回到本实例启动时的 yml 值"
        properties.getCache().setEnabled(true);
        service.delete(RuntimeConfigDomain.CACHE).block();
        Assertions.assertFalse(properties.getCache().isEnabled());

        List<Map<String, Object>> described = service.describe().block();
        Assertions.assertNotNull(described);
        Assertions.assertEquals(6, described.size());
        described.forEach(item -> Assertions.assertEquals("yml", item.get("source")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPersistSnapshotAndPublishVersionToken() {
        AiGatewayProperties properties = new AiGatewayProperties();
        RuntimeConfigRepository repository = repository();
        RuntimeConfigVersions versions = Mockito.mock(RuntimeConfigVersions.class);
        Mockito.when(versions.nextToken()).thenReturn(Mono.just(42L));
        Mockito.when(versions.publish(Mockito.anyString(), Mockito.anyLong())).thenReturn(Mono.empty());
        ArgumentCaptor<RuntimeConfigEntity> saved = ArgumentCaptor.forClass(RuntimeConfigEntity.class);
        Mockito.when(repository.save(saved.capture())).thenReturn(Mono.just(new RuntimeConfigEntity()));

        RuntimeConfigService service = service(properties, repository, versions);
        service.initialize();

        Long token = service.persist(RuntimeConfigDomain.CACHE, Map.of("ttlSeconds", 90L)).block();

        Assertions.assertEquals(42L, token);
        Assertions.assertEquals("cache", saved.getValue().getDomain());
        Assertions.assertEquals(42L, saved.getValue().getVersion());
        Assertions.assertEquals(Map.of("ttlSeconds", 90), read(saved.getValue().getConfigJson()));
        Mockito.verify(versions).publish("cache", 42L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReuseExistingRowWhenPersisting() {
        AiGatewayProperties properties = new AiGatewayProperties();
        RuntimeConfigRepository repository = repository();
        RuntimeConfigEntity existing = row("cache", Map.of("ttlSeconds", 10L), 1L);
        Mockito.when(repository.findByDomain("cache")).thenReturn(Mono.just(existing));
        ArgumentCaptor<RuntimeConfigEntity> saved = ArgumentCaptor.forClass(RuntimeConfigEntity.class);
        Mockito.when(repository.save(saved.capture())).thenReturn(Mono.just(existing));
        RuntimeConfigVersions versions = Mockito.mock(RuntimeConfigVersions.class);
        Mockito.when(versions.nextToken()).thenReturn(Mono.just(2L));
        Mockito.when(versions.publish(Mockito.anyString(), Mockito.anyLong())).thenReturn(Mono.empty());

        RuntimeConfigService service = service(properties, repository, versions);
        service.initialize();
        service.persist(RuntimeConfigDomain.CACHE, Map.of("ttlSeconds", 10L)).block();

        Assertions.assertSame(existing, saved.getValue(), "已有行应该被更新而不是插一行新的");
        Assertions.assertEquals(2L, existing.getVersion());
    }

    @Test
    void shouldDescribeSourceVersionAndForceFlag() {
        AiGatewayProperties properties = new AiGatewayProperties();
        RuntimeConfigRepository repository = repository();
        RuntimeConfigEntity cacheRow = row("cache", Map.of("ttlSeconds", 15L), 9L);
        cacheRow.setUpdatedAt(java.time.LocalDateTime.of(2026, 3, 5, 10, 30));
        Mockito.when(repository.findByDomain("cache")).thenReturn(Mono.just(cacheRow));
        Mockito.when(repository.findAll()).thenReturn(Flux.just(cacheRow));
        System.setProperty(RuntimeConfigSupport.SECURITY_FORCE_ENABLED_KEY, "true");

        RuntimeConfigService service = service(properties, repository);
        service.initialize();

        List<Map<String, Object>> described = service.describe().block();
        Assertions.assertNotNull(described);
        Map<String, Object> cache = described.stream()
                .filter(item -> "cache".equals(item.get("domain"))).findFirst().orElseThrow();
        Assertions.assertEquals("database", cache.get("source"));
        Assertions.assertEquals(9L, cache.get("version"));
        Assertions.assertEquals("2026-03-05T10:30", cache.get("updatedAt"));
        Assertions.assertEquals(15L, castEffective(cache).get("ttlSeconds"));

        Map<String, Object> routing = described.stream()
                .filter(item -> "routing".equals(item.get("domain"))).findFirst().orElseThrow();
        Assertions.assertEquals("yml", routing.get("source"));

        Map<String, Object> security = described.stream()
                .filter(item -> "security".equals(item.get("domain"))).findFirst().orElseThrow();
        Assertions.assertEquals(Boolean.TRUE, security.get("forcedOverridden"));
    }

    @Test
    void shouldFallBackToMemoryViewWhenDescribeCannotReadDatabase() {
        AiGatewayProperties properties = new AiGatewayProperties();
        RuntimeConfigRepository repository = repository();
        Mockito.when(repository.findAll()).thenReturn(Flux.error(new IllegalStateException("database down")));

        RuntimeConfigService service = service(properties, repository);
        service.initialize();

        List<Map<String, Object>> described = service.describe().block();

        Assertions.assertNotNull(described);
        Assertions.assertEquals(6, described.size());
        described.forEach(item -> Assertions.assertEquals("yml", item.get("source")));
    }

    @Test
    void shouldReportStoredVersionAndZeroWhenUnreadable() {
        AiGatewayProperties properties = new AiGatewayProperties();
        RuntimeConfigRepository repository = repository();
        Mockito.when(repository.findByDomain("cache")).thenReturn(Mono.just(row("cache", Map.of(), 11L)));
        Mockito.when(repository.findByDomain("safety"))
                .thenReturn(Mono.error(new IllegalStateException("database down")));

        RuntimeConfigService service = service(properties, repository);
        service.initialize();

        Assertions.assertEquals(11L, service.storedVersion(RuntimeConfigDomain.CACHE).block());
        Assertions.assertEquals(0L, service.storedVersion(RuntimeConfigDomain.SAFETY).block());
    }

    private RuntimeConfigService service(AiGatewayProperties properties, RuntimeConfigRepository repository) {
        return service(properties, repository, Mockito.mock(RuntimeConfigVersions.class));
    }

    @SuppressWarnings("unchecked")
    private RuntimeConfigService service(AiGatewayProperties properties,
                                         RuntimeConfigRepository repository,
                                         RuntimeConfigVersions versions) {
        ObjectProvider<RuntimeConfigRepository> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(repository);
        return new RuntimeConfigService(properties, provider, objectMapper, versions);
    }

    private RuntimeConfigRepository repository() {
        RuntimeConfigRepository repository = Mockito.mock(RuntimeConfigRepository.class);
        Mockito.when(repository.findByDomain(Mockito.anyString())).thenReturn(Mono.empty());
        Mockito.when(repository.findAll()).thenReturn(Flux.empty());
        return repository;
    }

    private RuntimeConfigEntity row(String domain, Map<String, Object> snapshot, long version) {
        RuntimeConfigEntity entity = new RuntimeConfigEntity();
        entity.setDomain(domain);
        entity.setVersion(version);
        try {
            entity.setConfigJson(objectMapper.writeValueAsString(snapshot));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return entity;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castEffective(Map<String, Object> item) {
        return (Map<String, Object>) item.get("effective");
    }
}
