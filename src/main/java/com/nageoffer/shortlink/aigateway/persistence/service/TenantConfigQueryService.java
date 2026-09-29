package com.nageoffer.shortlink.aigateway.persistence.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTenantProperties;
import com.nageoffer.shortlink.aigateway.crypto.AesGcmSecretCipher;
import com.nageoffer.shortlink.aigateway.crypto.SecretCipher;
import com.nageoffer.shortlink.aigateway.persistence.entity.AiModelPriceEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderCredentialEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantApiKeyEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantQuotaPolicyEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.AiModelPriceRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderCredentialKeyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderCredentialRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantApiKeyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelAllowedRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelMappingRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelPolicyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantQuotaPolicyRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 租户配置查询门面。
 * <p>
 * 读路径全部走内存快照，DB 只负责在变更后刷新快照，避免把数据库放进每请求的热路径。
 * <p>
 * 本类只做两件事：把查询转发到对应域，以及编排整批快照的刷新。真正的快照加载、字段映射与
 * 分层取值规则都在各域（{@link TenantApiKeyLookup}、{@link TenantModelPolicyLookup}、
 * {@link TenantQuotaPolicyLookup}、{@link ModelPriceLookup}、{@link ProviderCredentialLookup}）里，
 * 让"某张表的边界"与"读它的代码"一一对应，不再挤在一个类里互相看不见。
 * <p>
 * <b>刷新语义（整批快照是全有或全无）</b>：5 个域共 7 张表用一次 {@code Mono.zip} 一起读，
 * 任一张读失败或任一个仓库没装配，都视为整套快照不可信——先清空所有域再回退 yml。
 * 这样做的原因是各域之间存在引用关系（策略指向模型、凭证指向渠道），
 * 允许"能读几个算几个"会出现"密钥来自新快照、配额还停在旧快照"的撕裂状态。
 * <p>
 * 唯一的例外是渠道 Key 池：它是后加的表，且不与上述域交叉引用，因此单独加载、单独失败，
 * 读不到只降级为"Key 池不可用"。
 */
@Service
public class TenantConfigQueryService {

    private static final Logger log = LoggerFactory.getLogger(TenantConfigQueryService.class);

    private final AiGatewayProperties properties;

    private final TenantApiKeyLookup apiKeys;

    private final TenantModelPolicyLookup modelPolicies;

    private final TenantQuotaPolicyLookup quotaPolicies;

    private final ModelPriceLookup modelPrices;

    private final ProviderCredentialLookup providerCredentials;

    @Autowired
    public TenantConfigQueryService(AiGatewayProperties properties,
                                    ObjectProvider<TenantApiKeyRepository> tenantApiKeyRepository,
                                    ObjectProvider<TenantModelPolicyRepository> tenantModelPolicyRepository,
                                    ObjectProvider<TenantModelAllowedRepository> tenantModelAllowedRepository,
                                    ObjectProvider<TenantModelMappingRepository> tenantModelMappingRepository,
                                    ObjectProvider<TenantQuotaPolicyRepository> tenantQuotaPolicyRepository,
                                    ObjectProvider<AiModelPriceRepository> aiModelPriceRepository,
                                    ObjectProvider<ProviderCredentialRepository> providerCredentialRepository,
                                    ObjectProvider<ProviderCredentialKeyRepository> providerCredentialKeyRepository,
                                    SecretCipher secretCipher) {
        this(properties,
                tenantApiKeyRepository.getIfAvailable(),
                tenantModelPolicyRepository.getIfAvailable(),
                tenantModelAllowedRepository.getIfAvailable(),
                tenantModelMappingRepository.getIfAvailable(),
                tenantQuotaPolicyRepository.getIfAvailable(),
                aiModelPriceRepository.getIfAvailable(),
                providerCredentialRepository.getIfAvailable(),
                providerCredentialKeyRepository.getIfAvailable(),
                secretCipher);
    }

    public TenantConfigQueryService(AiGatewayProperties properties) {
        this(properties,
                (TenantApiKeyRepository) null,
                (TenantModelPolicyRepository) null,
                (TenantModelAllowedRepository) null,
                (TenantModelMappingRepository) null,
                (TenantQuotaPolicyRepository) null,
                (AiModelPriceRepository) null,
                (ProviderCredentialRepository) null,
                (ProviderCredentialKeyRepository) null);
    }

    /**
     * 不加密的装配：给单测与"接库但没配主密钥"的场景用。
     */
    public TenantConfigQueryService(AiGatewayProperties properties,
                                    TenantApiKeyRepository tenantApiKeyRepository,
                                    TenantModelPolicyRepository tenantModelPolicyRepository,
                                    TenantModelAllowedRepository tenantModelAllowedRepository,
                                    TenantModelMappingRepository tenantModelMappingRepository,
                                    TenantQuotaPolicyRepository tenantQuotaPolicyRepository,
                                    AiModelPriceRepository aiModelPriceRepository,
                                    ProviderCredentialRepository providerCredentialRepository,
                                    ProviderCredentialKeyRepository providerCredentialKeyRepository) {
        this(properties, tenantApiKeyRepository, tenantModelPolicyRepository, tenantModelAllowedRepository,
                tenantModelMappingRepository, tenantQuotaPolicyRepository, aiModelPriceRepository,
                providerCredentialRepository, providerCredentialKeyRepository, AesGcmSecretCipher.disabled());
    }

    public TenantConfigQueryService(AiGatewayProperties properties,
                                    TenantApiKeyRepository tenantApiKeyRepository,
                                    TenantModelPolicyRepository tenantModelPolicyRepository,
                                    TenantModelAllowedRepository tenantModelAllowedRepository,
                                    TenantModelMappingRepository tenantModelMappingRepository,
                                    TenantQuotaPolicyRepository tenantQuotaPolicyRepository,
                                    AiModelPriceRepository aiModelPriceRepository,
                                    ProviderCredentialRepository providerCredentialRepository,
                                    ProviderCredentialKeyRepository providerCredentialKeyRepository,
                                    SecretCipher secretCipher) {
        this.properties = properties;
        this.apiKeys = new TenantApiKeyLookup(properties, tenantApiKeyRepository, secretCipher);
        this.modelPolicies = new TenantModelPolicyLookup(properties,
                tenantModelPolicyRepository, tenantModelAllowedRepository, tenantModelMappingRepository);
        this.quotaPolicies = new TenantQuotaPolicyLookup(properties, tenantQuotaPolicyRepository);
        this.modelPrices = new ModelPriceLookup(properties, aiModelPriceRepository);
        this.providerCredentials = new ProviderCredentialLookup(properties,
                providerCredentialRepository, providerCredentialKeyRepository, secretCipher);
    }

    /**
     * 只读 yml 的实例。给单测与"单实例部署且不接库"的场景用。
     */
    public static TenantConfigQueryService fallbackOnly(AiGatewayProperties properties) {
        return new TenantConfigQueryService(properties);
    }

    @PostConstruct
    public void refreshFromDatabase() {
        refreshFromDatabaseReactive().block();
    }

    public Mono<Void> refreshFromDatabaseReactive() {
        if (!DomainLookupSupport.snapshotEnabled(properties)) {
            return Mono.empty();
        }
        if (!apiKeys.ready() || !modelPolicies.ready() || !quotaPolicies.ready()
                || !modelPrices.ready() || !providerCredentials.ready()) {
            log.warn("tenant persistence is enabled but R2DBC repositories are unavailable, fallback to application.yml");
            return Mono.empty();
        }
        return Mono.zip(
                        apiKeys.load(),
                        modelPolicies.load(),
                        quotaPolicies.load(),
                        modelPrices.load(),
                        providerCredentials.load())
                .doOnNext(tuple -> applySnapshot(tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4(), tuple.getT5()))
                .doOnSuccess(ignored -> log.info("loaded tenant config from database: apiKeys={}, modelPolicies={}, quotaPolicies={}, modelPrices={}, providerCredentials={}",
                        apiKeys.size(), modelPolicies.size(), quotaPolicies.size(), modelPrices.size(), providerCredentials.size()))
                .doOnError(ex -> {
                    log.warn("failed to load tenant config from database, fallback to application.yml", ex);
                    clearAll();
                })
                .onErrorResume(ex -> Mono.empty())
                // Key 池单独加载：它是后加的表，缺失时不应该把整套租户配置一起拖挂
                .then(providerCredentials.loadKeyPool());
    }

    /**
     * 整批替换：先清空所有域，再逐域灌入。清空这一步不能省——某个域没有可灌的数据（例如库里本来就是空的），
     * 也必须让它回到"空"而不是留着上一次的旧快照。
     */
    private void applySnapshot(List<TenantApiKeyEntity> apiKeyEntities,
                               TenantModelPolicyLookup.Snapshot modelPolicySnapshot,
                               List<TenantQuotaPolicyEntity> quotaPolicyEntities,
                               List<AiModelPriceEntity> modelPriceEntities,
                               List<ProviderCredentialEntity> providerCredentialEntities) {
        clearAll();
        apiKeys.apply(apiKeyEntities);
        modelPolicies.apply(modelPolicySnapshot);
        quotaPolicies.apply(quotaPolicyEntities);
        modelPrices.apply(modelPriceEntities);
        providerCredentials.apply(providerCredentialEntities);
    }

    private void clearAll() {
        apiKeys.clear();
        modelPolicies.clear();
        quotaPolicies.clear();
        // 只清人工覆盖层：自动同步价格来自 models.dev，与本地库无关，库抖一下不该把它一起抹掉
        modelPrices.clear();
        providerCredentials.clear();
    }

    public Optional<AiGatewayTenantProperties.TenantApiKeyCredential> findApiKeyCredential(String apiKey) {
        return apiKeys.find(apiKey);
    }

    public Optional<AiGatewayTenantProperties.TenantModelPolicy> findModelPolicy(String tenantId) {
        return modelPolicies.find(tenantId);
    }

    public Optional<AiGatewayTenantProperties.TenantQuotaPolicy> findQuotaPolicy(String tenantId) {
        return quotaPolicies.find(tenantId);
    }

    /**
     * 模型单价，三层取第一层命中的：人工覆盖（DB）-> yml 手写 -> 自动同步。
     */
    public Optional<AiGatewayProperties.ModelPrice> findModelPrice(String model) {
        return modelPrices.find(model);
    }

    /**
     * 查询渠道 Key 池：DB 优先，未配置时回退到 yml 里的 {@code provider-credentials.<provider>.api-keys}。
     * <p>
     * 两者都为空时返回空列表，调用方回落到单 Key 的 {@code apiKey}。
     */
    public List<AiGatewayProperties.ProviderApiKey> findProviderKeys(String tenantId, String provider) {
        return providerCredentials.findKeys(tenantId, provider);
    }

    /**
     * 仅查询自动同步层，供成本估算的诊断与测试使用。
     */
    public Optional<AiGatewayProperties.ModelPrice> findSyncedModelPrice(String model) {
        return modelPrices.findSynced(model);
    }

    /**
     * 用一次同步的结果整体替换自动同步价格快照。
     */
    public void applySyncedPrices(Map<String, AiGatewayProperties.ModelPrice> prices) {
        modelPrices.applySynced(prices);
    }

    public int syncedPriceCount() {
        return modelPrices.syncedSize();
    }

    /**
     * 查询租户 BYOK 上游凭证。
     */
    public Optional<AiGatewayProperties.ProviderCredential> findProviderCredential(String tenantId, String provider) {
        return providerCredentials.find(tenantId, provider);
    }

    /**
     * 查询平台级上游凭证。
     */
    public Optional<AiGatewayProperties.ProviderCredential> findGlobalProviderCredential(String provider) {
        return providerCredentials.findGlobal(provider);
    }
}
