package com.nageoffer.shortlink.aigateway.persistence.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTenantProperties;
import com.nageoffer.shortlink.aigateway.persistence.entity.AiModelPriceEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderCredentialEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderCredentialKeyEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantApiKeyEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelAllowedEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelMappingEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelPolicyEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantQuotaPolicyEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.AiModelPriceRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderCredentialKeyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderCredentialRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantApiKeyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelAllowedRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelMappingRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelPolicyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantQuotaPolicyRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据库快照路径的行为固化测试。
 * <p>
 * {@code refreshFromDatabaseReactive} 是"DB 优先、yml 兜底"的唯一开关，但它此前没有任何测试：
 * 其余测试要么把 {@code persistence.enabled} 关掉，要么用 {@code fallbackOnly} 只读 yml。
 * 这条路径上有几个一旦改错就只在线上显现的语义，必须先钉死再动结构：
 * <ul>
 *   <li><b>整体替换</b>——刷新是全量覆盖而不是增量合并，旧记录必须消失；</li>
 *   <li><b>全有或全无</b>——任意一个仓库失败要清空全部域并整体回退 yml，不能留下半套快照；</li>
 *   <li><b>Key 池例外</b>——Key 池是后加的表，它失败不能把整租户配置一起拖挂；</li>
 *   <li><b>价格三层</b>——人工覆盖(DB) &gt; yml &gt; 自动同步，分层只在这一处定义。</li>
 * </ul>
 */
class TenantConfigQueryServiceSnapshotTest {

    private static final String TENANT = "tenant-a";

    /**
     * 八个仓库的桩集合。默认全部返回空流，单个用例按需覆盖。
     */
    private static final class Repos {

        private final TenantApiKeyRepository apiKey = Mockito.mock(TenantApiKeyRepository.class);

        private final TenantModelPolicyRepository policy = Mockito.mock(TenantModelPolicyRepository.class);

        private final TenantModelAllowedRepository allowed = Mockito.mock(TenantModelAllowedRepository.class);

        private final TenantModelMappingRepository mapping = Mockito.mock(TenantModelMappingRepository.class);

        private final TenantQuotaPolicyRepository quota = Mockito.mock(TenantQuotaPolicyRepository.class);

        private final AiModelPriceRepository price = Mockito.mock(AiModelPriceRepository.class);

        private final ProviderCredentialRepository credential = Mockito.mock(ProviderCredentialRepository.class);

        private final ProviderCredentialKeyRepository credentialKey =
                Mockito.mock(ProviderCredentialKeyRepository.class);
    }

    @Test
    void shouldReadOnlyYmlWhenPersistenceDisabled() {
        AiGatewayProperties properties = persistenceProperties(false);
        properties.getTenant().getApiKeys().put("k1", ymlApiKey("sk-yml", TENANT));
        Repos repos = new Repos();
        TenantConfigQueryService service = service(properties, repos);

        service.refreshFromDatabaseReactive().block();

        // persistence 关闭时连仓库都不该碰，避免"配置说关、实际还在查库"
        Mockito.verifyNoInteractions(repos.apiKey);
        Assertions.assertEquals(TENANT, service.findApiKeyCredential("sk-yml").orElseThrow().getTenantId());
    }

    @Test
    void shouldPreferDatabaseOverYmlAfterRefresh() {
        AiGatewayProperties properties = persistenceProperties(true);
        properties.getTenant().getApiKeys().put("k1", ymlApiKey("sk-shared", "yml-tenant"));
        Repos repos = stubEmpty();
        Mockito.when(repos.apiKey.findAll()).thenReturn(Flux.just(apiKeyEntity(TENANT, "app-x", "kid", "sk-shared")));
        TenantConfigQueryService service = service(properties, repos);

        service.refreshFromDatabaseReactive().block();

        Assertions.assertEquals(TENANT, service.findApiKeyCredential("sk-shared").orElseThrow().getTenantId(),
                "同一 apiKey 在 DB 与 yml 都存在时，DB 必须赢");
    }

    @Test
    void shouldFallBackToYmlWhenDatabaseHasNoEntry() {
        AiGatewayProperties properties = persistenceProperties(true);
        properties.getTenant().getQuotaPolicies().put(TENANT, ymlQuotaPolicy(100L, 200L));
        Repos repos = stubEmpty();
        Mockito.when(repos.quota.findAll()).thenReturn(Flux.just(quotaEntity("other-tenant", 999L)));
        TenantConfigQueryService service = service(properties, repos);

        service.refreshFromDatabaseReactive().block();

        Assertions.assertEquals(100L, service.findQuotaPolicy(TENANT).orElseThrow().getTokenQuotaPerMinute());
    }

    @Test
    void shouldReplaceSnapshotAsAWholeInsteadOfMerging() {
        AiGatewayProperties properties = persistenceProperties(true);
        Repos repos = stubEmpty();
        Mockito.when(repos.apiKey.findAll()).thenReturn(Flux.just(apiKeyEntity(TENANT, "app-x", "kid1", "sk-first")));
        TenantConfigQueryService service = service(properties, repos);

        service.refreshFromDatabaseReactive().block();
        Assertions.assertTrue(service.findApiKeyCredential("sk-first").isPresent());

        // 第二次刷新里 sk-first 已被删除：全量覆盖意味着旧值必须消失，而不是留在快照里继续放行
        Mockito.when(repos.apiKey.findAll()).thenReturn(Flux.just(apiKeyEntity(TENANT, "app-x", "kid2", "sk-second")));
        service.refreshFromDatabaseReactive().block();

        Assertions.assertTrue(service.findApiKeyCredential("sk-first").isEmpty(),
                "刷新是全量替换，已删除的 Key 不能残留");
        Assertions.assertTrue(service.findApiKeyCredential("sk-second").isPresent());
    }

    @Test
    void shouldClearEverythingAndFallBackToYmlWhenAnyRepositoryFails() {
        AiGatewayProperties properties = persistenceProperties(true);
        properties.getTenant().getQuotaPolicies().put(TENANT, ymlQuotaPolicy(100L, 200L));
        Repos repos = stubEmpty();
        Mockito.when(repos.apiKey.findAll()).thenReturn(Flux.just(apiKeyEntity(TENANT, "app-x", "kid", "sk-db")));
        TenantConfigQueryService service = service(properties, repos);

        service.refreshFromDatabaseReactive().block();
        Assertions.assertTrue(service.findApiKeyCredential("sk-db").isPresent());

        // 一个域读库失败 = 整套快照不可信：必须清空所有域并回退 yml，
        // 否则会出现"密钥来自新快照、配额还停在旧快照"的撕裂状态
        Mockito.when(repos.quota.findAll()).thenReturn(Flux.error(new IllegalStateException("db down")));
        service.refreshFromDatabaseReactive().block();

        Assertions.assertTrue(service.findApiKeyCredential("sk-db").isEmpty(), "任一仓库失败要清空全部域");
        Assertions.assertEquals(100L, service.findQuotaPolicy(TENANT).orElseThrow().getTokenQuotaPerMinute(),
                "清空后必须整体回退到 yml");
    }

    @Test
    void shouldNotApplyAnySnapshotWhenRepositoryBeanIsMissing() {
        AiGatewayProperties properties = persistenceProperties(true);
        properties.getTenant().getQuotaPolicies().put(TENANT, ymlQuotaPolicy(100L, 200L));
        Repos repos = stubEmpty();
        Mockito.when(repos.quota.findAll()).thenReturn(Flux.just(quotaEntity(TENANT, 999L)));
        // 凭证仓库未装配（例如没引 R2DBC）：此时整套 DB 读取都不该生效，而不是"能读几个算几个"
        TenantConfigQueryService service = new TenantConfigQueryService(properties,
                repos.apiKey, repos.policy, repos.allowed, repos.mapping, repos.quota, repos.price,
                null, repos.credentialKey);

        service.refreshFromDatabaseReactive().block();

        Assertions.assertEquals(100L, service.findQuotaPolicy(TENANT).orElseThrow().getTokenQuotaPerMinute());
    }

    @Test
    void shouldLoadKeyPoolInFallbackOrderAndKeepItIsolated() {
        AiGatewayProperties properties = persistenceProperties(true);
        properties.getUpstream().getProviderCredentials().put("openai", ymlPlatformCredential("sk-yml-platform"));
        Repos repos = stubEmpty();
        Mockito.when(repos.credential.findAll())
                .thenReturn(Flux.just(credentialEntity(TENANT, "openai", "sk-tenant-byok", "{}")));
        Mockito.when(repos.credentialKey.findAll())
                .thenReturn(Flux.just(keyEntity(TENANT, "openai", "kid-1", "sk-pool", null, true)));
        TenantConfigQueryService service = service(properties, repos);

        service.refreshFromDatabaseReactive().block();
        Assertions.assertEquals(List.of("sk-pool"),
                service.findProviderKeys(TENANT, "openai").stream()
                        .map(AiGatewayProperties.ProviderApiKey::getApiKey).toList());

        // Key 池读库失败是后加表的常见故障：主快照（BYOK 凭证）必须留着，Key 池自行回落到 yml
        Mockito.when(repos.credentialKey.findAll()).thenReturn(Flux.error(new IllegalStateException("table missing")));
        service.refreshFromDatabaseReactive().block();

        Assertions.assertEquals("sk-tenant-byok",
                service.findProviderCredential(TENANT, "openai").orElseThrow().getApiKey(),
                "Key 池失败不能把已经加载好的主快照一起清掉");
        Assertions.assertTrue(service.findProviderKeys(TENANT, "openai").isEmpty(),
                "Key 池失败后回落到 yml，而 yml 里没有配 api-keys");
    }

    @Test
    void shouldGroupKeyPoolByTenantAndProviderAndSkipUnusableKeys() {
        AiGatewayProperties properties = persistenceProperties(true);
        Repos repos = stubEmpty();
        Mockito.when(repos.credentialKey.findAll()).thenReturn(Flux.just(
                keyEntity(TENANT, "openai", "kid-1", "sk-1", null, true),
                keyEntity(TENANT, "openai", "kid-2", "sk-2", 5, true),
                keyEntity(TENANT, "openai", "kid-3", "sk-disabled", 1, false),
                keyEntity(TENANT, "openai", "kid-4", null, 1, true),
                keyEntity("tenant-b", "openai", "kid-5", "sk-other-tenant", 1, true)
        ));
        TenantConfigQueryService service = service(properties, repos);

        service.refreshFromDatabaseReactive().block();

        List<AiGatewayProperties.ProviderApiKey> keys = service.findProviderKeys(TENANT, "openai");
        Assertions.assertEquals(2, keys.size(), "禁用的与缺 apiKey 的条目都要被丢掉");
        // 未填权重按 1 处理，避免下游加权随机拿到 null
        Assertions.assertEquals(1, keys.get(0).getWeight());
        Assertions.assertEquals(5, keys.get(1).getWeight());
        // 隔离到租户：不能串到别人的 Key
        Assertions.assertEquals(1, service.findProviderKeys("tenant-b", "openai").size());
        Assertions.assertTrue(service.findProviderKeys(TENANT, "claude").isEmpty());
    }

    @Test
    void shouldLayerModelPriceAsManualThenYmlThenSynced() {
        AiGatewayProperties properties = persistenceProperties(true);
        properties.getObservability().getModelPrice().put("m-yml", price(1D, 2D));
        Repos repos = stubEmpty();
        Mockito.when(repos.price.findAll()).thenReturn(Flux.just(
                priceEntity("m-manual", 3D, 4D, true),
                priceEntity("m-disabled", 9D, 9D, false)));
        TenantConfigQueryService service = service(properties, repos);
        service.refreshFromDatabaseReactive().block();
        service.applySyncedPrices(Map.of("m-synced", price(5D, 6D), "m-yml", price(7D, 8D)));

        Assertions.assertEquals(3D, service.findModelPrice("m-manual").orElseThrow().getInputPer1k());
        // 人工写过的 yml 价格必须盖住自动同步来的价格
        Assertions.assertEquals(1D, service.findModelPrice("m-yml").orElseThrow().getInputPer1k());
        Assertions.assertEquals(5D, service.findModelPrice("m-synced").orElseThrow().getInputPer1k());
        Assertions.assertTrue(service.findModelPrice("m-disabled").isEmpty(), "enabled=false 的价格不生效");
        // 自动同步层可被单独诊断，不受上层覆盖影响
        Assertions.assertEquals(7D, service.findSyncedModelPrice("m-yml").orElseThrow().getInputPer1k());
        Assertions.assertEquals(2, service.syncedPriceCount());
        // 同步结果是整体替换，不是累加
        service.applySyncedPrices(Map.of("m-only", price(1D, 1D)));
        Assertions.assertEquals(1, service.syncedPriceCount());
        Assertions.assertTrue(service.findSyncedModelPrice("m-synced").isEmpty());
    }

    @Test
    void shouldParseExtraHeadersJsonAndTolerateInvalidContent() {
        AiGatewayProperties properties = persistenceProperties(true);
        Repos repos = stubEmpty();
        Mockito.when(repos.credential.findAll()).thenReturn(Flux.just(
                credentialEntity(TENANT, "claude", "sk-1", "{\"x-a\":\"1\",\"x-b\":\"2\"}"),
                credentialEntity("tenant-b", "claude", "sk-2", "not-a-json"),
                credentialEntity("tenant-c", "claude", "sk-3", "  "),
                credentialEntity("tenant-d", "claude", "sk-disabled", "{}", false)
        ));
        TenantConfigQueryService service = service(properties, repos);

        service.refreshFromDatabaseReactive().block();

        Assertions.assertEquals(Map.of("x-a", "1", "x-b", "2"),
                service.findProviderCredential(TENANT, "claude").orElseThrow().getExtraHeaders());
        // 脏数据不能让整条凭证失效：宁可不带附加头，也不能把渠道搞挂
        Assertions.assertTrue(service.findProviderCredential("tenant-b", "claude").orElseThrow()
                .getExtraHeaders().isEmpty());
        Assertions.assertTrue(service.findProviderCredential("tenant-c", "claude").orElseThrow()
                .getExtraHeaders().isEmpty());
        Assertions.assertTrue(service.findProviderCredential("tenant-d", "claude").isEmpty(),
                "enabled=false 的凭证不入快照");
    }

    @Test
    void shouldResolveGlobalCredentialByGlobalTenantMarker() {
        AiGatewayProperties properties = persistenceProperties(true);
        properties.getUpstream().getProviderCredentials().put("openai", ymlPlatformCredential("sk-yml-platform"));
        Repos repos = stubEmpty();
        Mockito.when(repos.credential.findAll()).thenReturn(Flux.just(
                credentialEntity(ProviderCredentialEntity.GLOBAL_TENANT_ID, "openai", "sk-global", "{}"),
                credentialEntity(TENANT, "openai", "sk-tenant", "{}")
        ));
        TenantConfigQueryService service = service(properties, repos);

        service.refreshFromDatabaseReactive().block();

        Assertions.assertEquals("sk-global",
                service.findGlobalProviderCredential("openai").orElseThrow().getApiKey());
        Assertions.assertEquals("sk-tenant",
                service.findProviderCredential(TENANT, "openai").orElseThrow().getApiKey());
        // 全局查询走的是 "*" 这个租户标记，不能被某个租户的 BYOK 命中；反之未知租户也不该拿到平台凭证
        Assertions.assertTrue(service.findProviderCredential("tenant-unknown", "openai").isEmpty(),
                "平台级凭证只对 GLOBAL_TENANT_ID 与 yml 生效，不能当成任意租户的 BYOK");
    }

    @Test
    void shouldBindAllowedModelsAndMappingsToTheirOwnTenantOnly() {
        AiGatewayProperties properties = persistenceProperties(true);
        Repos repos = stubEmpty();
        Mockito.when(repos.policy.findAll()).thenReturn(Flux.just(
                policyEntity(TENANT, true, "default", "gpt-4o-mini"),
                policyEntity("tenant-b", true, "default", "claude-3-5-sonnet-latest")
        ));
        Mockito.when(repos.allowed.findAll()).thenReturn(Flux.just(
                allowedEntity(TENANT, "gpt-4o"),
                allowedEntity("tenant-b", "claude-3-5-sonnet-latest"),
                allowedEntity("tenant-orphan", "no-policy-row")
        ));
        Mockito.when(repos.mapping.findAll()).thenReturn(Flux.just(
                mappingEntity(TENANT, "req-model", "provider-model")
        ));
        TenantConfigQueryService service = service(properties, repos);

        service.refreshFromDatabaseReactive().block();

        AiGatewayTenantProperties.TenantModelPolicy policyA = service.findModelPolicy(TENANT).orElseThrow();
        Assertions.assertEquals(Set.of("gpt-4o"), policyA.getAllowedModels());
        Assertions.assertEquals(Map.of("req-model", "provider-model"), policyA.getModelMappings());

        AiGatewayTenantProperties.TenantModelPolicy policyB = service.findModelPolicy("tenant-b").orElseThrow();
        Assertions.assertEquals(Set.of("claude-3-5-sonnet-latest"), policyB.getAllowedModels());
        Assertions.assertTrue(policyB.getModelMappings().isEmpty());

        // 只有 allowed 行、没有策略行的租户不构成策略：否则会凭空放开一个未配置的租户
        Assertions.assertTrue(service.findModelPolicy("tenant-orphan").isEmpty());
    }

    // ------------------------------------------------------------------
    // 装配辅助
    // ------------------------------------------------------------------

    private TenantConfigQueryService service(AiGatewayProperties properties, Repos repos) {
        return new TenantConfigQueryService(properties,
                repos.apiKey, repos.policy, repos.allowed, repos.mapping, repos.quota, repos.price,
                repos.credential, repos.credentialKey);
    }

    private Repos stubEmpty() {
        Repos repos = new Repos();
        Mockito.when(repos.apiKey.findAll()).thenReturn(Flux.empty());
        Mockito.when(repos.policy.findAll()).thenReturn(Flux.empty());
        Mockito.when(repos.allowed.findAll()).thenReturn(Flux.empty());
        Mockito.when(repos.mapping.findAll()).thenReturn(Flux.empty());
        Mockito.when(repos.quota.findAll()).thenReturn(Flux.empty());
        Mockito.when(repos.price.findAll()).thenReturn(Flux.empty());
        Mockito.when(repos.credential.findAll()).thenReturn(Flux.empty());
        Mockito.when(repos.credentialKey.findAll()).thenReturn(Flux.empty());
        return repos;
    }

    private AiGatewayProperties persistenceProperties(boolean enabled) {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getTenant().getPersistence().setEnabled(enabled);
        return properties;
    }

    private AiGatewayTenantProperties.TenantApiKeyCredential ymlApiKey(String apiKey, String tenantId) {
        AiGatewayTenantProperties.TenantApiKeyCredential credential = new AiGatewayTenantProperties.TenantApiKeyCredential();
        credential.setApiKey(apiKey);
        credential.setTenantId(tenantId);
        credential.setAppId("app-yml");
        return credential;
    }

    private AiGatewayTenantProperties.TenantQuotaPolicy ymlQuotaPolicy(Long minute, Long day) {
        AiGatewayTenantProperties.TenantQuotaPolicy policy = new AiGatewayTenantProperties.TenantQuotaPolicy();
        policy.setTokenQuotaPerMinute(minute);
        policy.setTokenQuotaPerDay(day);
        return policy;
    }

    private AiGatewayProperties.ProviderCredential ymlPlatformCredential(String apiKey) {
        AiGatewayProperties.ProviderCredential credential = new AiGatewayProperties.ProviderCredential();
        credential.setApiKey(apiKey);
        return credential;
    }

    private AiGatewayProperties.ModelPrice price(double input, double output) {
        AiGatewayProperties.ModelPrice price = new AiGatewayProperties.ModelPrice();
        price.setInputPer1k(input);
        price.setOutputPer1k(output);
        return price;
    }

    private TenantApiKeyEntity apiKeyEntity(String tenantId, String appId, String keyId, String apiKey) {
        TenantApiKeyEntity entity = new TenantApiKeyEntity();
        entity.setTenantId(tenantId);
        entity.setAppId(appId);
        entity.setKeyId(keyId);
        entity.setApiKey(apiKey);
        entity.setEnabled(true);
        return entity;
    }

    private TenantModelPolicyEntity policyEntity(String tenantId, Boolean enabled, String alias, String defaultModel) {
        TenantModelPolicyEntity entity = new TenantModelPolicyEntity();
        entity.setTenantId(tenantId);
        entity.setEnabled(enabled);
        entity.setDefaultModelAlias(alias);
        entity.setDefaultModel(defaultModel);
        return entity;
    }

    private TenantModelAllowedEntity allowedEntity(String tenantId, String allowedModel) {
        TenantModelAllowedEntity entity = new TenantModelAllowedEntity();
        entity.setTenantId(tenantId);
        entity.setAllowedModel(allowedModel);
        return entity;
    }

    private TenantModelMappingEntity mappingEntity(String tenantId, String requestModel, String providerModel) {
        TenantModelMappingEntity entity = new TenantModelMappingEntity();
        entity.setTenantId(tenantId);
        entity.setRequestModel(requestModel);
        entity.setProviderModel(providerModel);
        return entity;
    }

    private TenantQuotaPolicyEntity quotaEntity(String tenantId, Long minute) {
        TenantQuotaPolicyEntity entity = new TenantQuotaPolicyEntity();
        entity.setTenantId(tenantId);
        entity.setEnabled(true);
        entity.setTokenQuotaPerMinute(minute);
        return entity;
    }

    private AiModelPriceEntity priceEntity(String model, Double input, Double output, Boolean enabled) {
        AiModelPriceEntity entity = new AiModelPriceEntity();
        entity.setModel(model);
        entity.setInputPer1k(input);
        entity.setOutputPer1k(output);
        entity.setEnabled(enabled);
        return entity;
    }

    private ProviderCredentialEntity credentialEntity(String tenantId, String provider, String apiKey, String extraHeaders) {
        return credentialEntity(tenantId, provider, apiKey, extraHeaders, true);
    }

    private ProviderCredentialEntity credentialEntity(String tenantId, String provider, String apiKey,
                                                      String extraHeaders, Boolean enabled) {
        ProviderCredentialEntity entity = new ProviderCredentialEntity();
        entity.setTenantId(tenantId);
        entity.setProvider(provider);
        entity.setApiKey(apiKey);
        entity.setExtraHeaders(extraHeaders);
        entity.setEnabled(enabled);
        return entity;
    }

    private ProviderCredentialKeyEntity keyEntity(String tenantId, String provider, String keyId, String apiKey,
                                                 Integer weight, Boolean enabled) {
        ProviderCredentialKeyEntity entity = new ProviderCredentialKeyEntity();
        entity.setTenantId(tenantId);
        entity.setProvider(provider);
        entity.setKeyId(keyId);
        entity.setApiKey(apiKey);
        entity.setWeight(weight);
        entity.setEnabled(enabled);
        return entity;
    }
}
