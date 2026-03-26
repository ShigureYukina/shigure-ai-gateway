package com.nageoffer.shortlink.aigateway.persistence.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.entity.AiModelPriceEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantApiKeyEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelAllowedEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelMappingEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelPolicyEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantQuotaPolicyEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.AiModelPriceRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantApiKeyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelAllowedRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelMappingRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelPolicyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantQuotaPolicyRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class TenantConfigQueryService {

    private static final Logger log = LoggerFactory.getLogger(TenantConfigQueryService.class);

    private final AiGatewayProperties properties;
    private final TenantApiKeyRepository tenantApiKeyRepository;
    private final TenantModelPolicyRepository tenantModelPolicyRepository;
    private final TenantModelAllowedRepository tenantModelAllowedRepository;
    private final TenantModelMappingRepository tenantModelMappingRepository;
    private final TenantQuotaPolicyRepository tenantQuotaPolicyRepository;
    private final AiModelPriceRepository aiModelPriceRepository;

    private final Map<String, AiGatewayProperties.TenantApiKeyCredential> apiKeyCache = new ConcurrentHashMap<>();
    private final Map<String, AiGatewayProperties.TenantModelPolicy> modelPolicyCache = new ConcurrentHashMap<>();
    private final Map<String, AiGatewayProperties.TenantQuotaPolicy> quotaPolicyCache = new ConcurrentHashMap<>();
    private final Map<String, AiGatewayProperties.ModelPrice> modelPriceCache = new ConcurrentHashMap<>();

    @Autowired
    public TenantConfigQueryService(AiGatewayProperties properties,
                                    ObjectProvider<TenantApiKeyRepository> tenantApiKeyRepository,
                                    ObjectProvider<TenantModelPolicyRepository> tenantModelPolicyRepository,
                                    ObjectProvider<TenantModelAllowedRepository> tenantModelAllowedRepository,
                                    ObjectProvider<TenantModelMappingRepository> tenantModelMappingRepository,
                                    ObjectProvider<TenantQuotaPolicyRepository> tenantQuotaPolicyRepository,
                                    ObjectProvider<AiModelPriceRepository> aiModelPriceRepository) {
        this(properties,
                tenantApiKeyRepository.getIfAvailable(),
                tenantModelPolicyRepository.getIfAvailable(),
                tenantModelAllowedRepository.getIfAvailable(),
                tenantModelMappingRepository.getIfAvailable(),
                tenantQuotaPolicyRepository.getIfAvailable(),
                aiModelPriceRepository.getIfAvailable());
    }

    public TenantConfigQueryService(AiGatewayProperties properties) {
        this(properties,
                (TenantApiKeyRepository) null,
                (TenantModelPolicyRepository) null,
                (TenantModelAllowedRepository) null,
                (TenantModelMappingRepository) null,
                (TenantQuotaPolicyRepository) null,
                (AiModelPriceRepository) null);
    }

    public TenantConfigQueryService(AiGatewayProperties properties,
                                    TenantApiKeyRepository tenantApiKeyRepository,
                                    TenantModelPolicyRepository tenantModelPolicyRepository,
                                    TenantModelAllowedRepository tenantModelAllowedRepository,
                                    TenantModelMappingRepository tenantModelMappingRepository,
                                    TenantQuotaPolicyRepository tenantQuotaPolicyRepository,
                                    AiModelPriceRepository aiModelPriceRepository) {
        this.properties = properties;
        this.tenantApiKeyRepository = tenantApiKeyRepository;
        this.tenantModelPolicyRepository = tenantModelPolicyRepository;
        this.tenantModelAllowedRepository = tenantModelAllowedRepository;
        this.tenantModelMappingRepository = tenantModelMappingRepository;
        this.tenantQuotaPolicyRepository = tenantQuotaPolicyRepository;
        this.aiModelPriceRepository = aiModelPriceRepository;
    }

    public static TenantConfigQueryService fallbackOnly(AiGatewayProperties properties) {
        return new TenantConfigQueryService(properties);
    }

    @PostConstruct
    public void refreshFromDatabase() {
        refreshFromDatabaseReactive().block();
    }

    public Mono<Void> refreshFromDatabaseReactive() {
        if (!properties.getTenant().getPersistence().isEnabled()) {
            return Mono.empty();
        }
        if (tenantApiKeyRepository == null || tenantModelPolicyRepository == null || tenantModelAllowedRepository == null
                || tenantModelMappingRepository == null || tenantQuotaPolicyRepository == null || aiModelPriceRepository == null) {
            log.warn("tenant persistence is enabled but R2DBC repositories are unavailable, fallback to application.yml");
            return Mono.empty();
        }
        return Mono.zip(
                        tenantApiKeyRepository.findAll().collectList(),
                        tenantModelPolicyRepository.findAll().collectList(),
                        tenantModelAllowedRepository.findAll().collectList(),
                        tenantModelMappingRepository.findAll().collectList(),
                        tenantQuotaPolicyRepository.findAll().collectList(),
                        aiModelPriceRepository.findAll().collectList()
                )
                .doOnNext(tuple -> applySnapshot(
                        tuple.getT1(),
                        tuple.getT2(),
                        tuple.getT3(),
                        tuple.getT4(),
                        tuple.getT5(),
                        tuple.getT6()))
                .doOnSuccess(ignored -> log.info("loaded tenant config from database: apiKeys={}, modelPolicies={}, quotaPolicies={}, modelPrices={}",
                        apiKeyCache.size(), modelPolicyCache.size(), quotaPolicyCache.size(), modelPriceCache.size()))
                .doOnError(ex -> {
                    log.warn("failed to load tenant config from database, fallback to application.yml", ex);
                    apiKeyCache.clear();
                    modelPolicyCache.clear();
                    quotaPolicyCache.clear();
                    modelPriceCache.clear();
                })
                .onErrorResume(ex -> Mono.empty())
                .then();
    }

    private void applySnapshot(List<TenantApiKeyEntity> apiKeys,
                               List<TenantModelPolicyEntity> modelPolicies,
                               List<TenantModelAllowedEntity> allowedModels,
                               List<TenantModelMappingEntity> modelMappings,
                               List<TenantQuotaPolicyEntity> quotaPolicies,
                               List<AiModelPriceEntity> modelPrices) {
        apiKeyCache.clear();
        modelPolicyCache.clear();
        quotaPolicyCache.clear();
        modelPriceCache.clear();

        apiKeys.forEach(each -> apiKeyCache.put(each.getApiKey(), toCredential(each)));
        Map<String, Set<String>> allowedByTenant = allowedModels.stream()
                .collect(Collectors.groupingBy(TenantModelAllowedEntity::getTenantId,
                        Collectors.mapping(TenantModelAllowedEntity::getAllowedModel, Collectors.toSet())));
        Map<String, Map<String, String>> mappingsByTenant = modelMappings.stream()
                .collect(Collectors.groupingBy(TenantModelMappingEntity::getTenantId,
                        Collectors.toMap(TenantModelMappingEntity::getRequestModel, TenantModelMappingEntity::getProviderModel, (left, right) -> right, HashMap::new)));
        modelPolicies.forEach(each -> modelPolicyCache.put(each.getTenantId(), toPolicy(each,
                allowedByTenant.getOrDefault(each.getTenantId(), Collections.emptySet()),
                mappingsByTenant.getOrDefault(each.getTenantId(), Collections.emptyMap()))));
        quotaPolicies.forEach(each -> quotaPolicyCache.put(each.getTenantId(), toQuotaPolicy(each)));
        modelPrices.stream()
                .filter(each -> Boolean.TRUE.equals(each.getEnabled()))
                .forEach(each -> modelPriceCache.put(each.getModel(), toModelPrice(each)));
    }

    public Optional<AiGatewayProperties.TenantApiKeyCredential> findApiKeyCredential(String apiKey) {
        if (properties.getTenant().getPersistence().isEnabled()) {
            AiGatewayProperties.TenantApiKeyCredential credential = apiKeyCache.get(apiKey);
            if (credential != null) {
                return Optional.of(credential);
            }
        }
        return properties.getTenant().getApiKeys().values().stream()
                .filter(each -> apiKey.equals(each.getApiKey()))
                .findFirst();
    }

    public Optional<AiGatewayProperties.TenantModelPolicy> findModelPolicy(String tenantId) {
        if (properties.getTenant().getPersistence().isEnabled()) {
            AiGatewayProperties.TenantModelPolicy policy = modelPolicyCache.get(tenantId);
            if (policy != null) {
                return Optional.of(policy);
            }
        }
        return Optional.ofNullable(properties.getTenant().getModelPolicies().get(tenantId));
    }

    public Optional<AiGatewayProperties.TenantQuotaPolicy> findQuotaPolicy(String tenantId) {
        if (properties.getTenant().getPersistence().isEnabled()) {
            AiGatewayProperties.TenantQuotaPolicy policy = quotaPolicyCache.get(tenantId);
            if (policy != null) {
                return Optional.of(policy);
            }
        }
        return Optional.ofNullable(properties.getTenant().getQuotaPolicies().get(tenantId));
    }

    public Optional<AiGatewayProperties.ModelPrice> findModelPrice(String model) {
        if (properties.getTenant().getPersistence().isEnabled()) {
            AiGatewayProperties.ModelPrice price = modelPriceCache.get(model);
            if (price != null) {
                return Optional.of(price);
            }
        }
        return Optional.ofNullable(properties.getObservability().getModelPrice().get(model));
    }

    private AiGatewayProperties.TenantApiKeyCredential toCredential(TenantApiKeyEntity entity) {
        AiGatewayProperties.TenantApiKeyCredential credential = new AiGatewayProperties.TenantApiKeyCredential();
        credential.setApiKey(entity.getApiKey());
        credential.setTenantId(entity.getTenantId());
        credential.setAppId(entity.getAppId());
        credential.setKeyId(entity.getKeyId());
        credential.setEnabled(Boolean.TRUE.equals(entity.getEnabled()));
        credential.setExpiresAt(entity.getExpiresAt());
        return credential;
    }

    private AiGatewayProperties.TenantModelPolicy toPolicy(TenantModelPolicyEntity entity, Set<String> allowedModels, Map<String, String> mappings) {
        AiGatewayProperties.TenantModelPolicy policy = new AiGatewayProperties.TenantModelPolicy();
        policy.setEnabled(Boolean.TRUE.equals(entity.getEnabled()));
        policy.setAllowedModels(allowedModels);
        policy.setModelMappings(mappings);
        policy.setDefaultModelAlias(entity.getDefaultModelAlias());
        policy.setDefaultModel(entity.getDefaultModel());
        return policy;
    }

    private AiGatewayProperties.TenantQuotaPolicy toQuotaPolicy(TenantQuotaPolicyEntity entity) {
        AiGatewayProperties.TenantQuotaPolicy policy = new AiGatewayProperties.TenantQuotaPolicy();
        policy.setEnabled(Boolean.TRUE.equals(entity.getEnabled()));
        policy.setTokenQuotaPerMinute(entity.getTokenQuotaPerMinute());
        policy.setTokenQuotaPerDay(entity.getTokenQuotaPerDay());
        policy.setTokenQuotaPerMonth(entity.getTokenQuotaPerMonth());
        return policy;
    }

    private AiGatewayProperties.ModelPrice toModelPrice(AiModelPriceEntity entity) {
        AiGatewayProperties.ModelPrice price = new AiGatewayProperties.ModelPrice();
        price.setInputPer1k(entity.getInputPer1k());
        price.setOutputPer1k(entity.getOutputPer1k());
        return price;
    }
}
