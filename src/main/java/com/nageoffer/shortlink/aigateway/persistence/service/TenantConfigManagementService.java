package com.nageoffer.shortlink.aigateway.persistence.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.persistence.entity.AiModelPriceEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantAppEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantApiKeyEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelAllowedEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelMappingEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelPolicyEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantQuotaPolicyEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.AiModelPriceRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantAppRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantApiKeyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelAllowedRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelMappingRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelPolicyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantQuotaPolicyRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@ConditionalOnProperty(prefix = "short-link.ai-gateway.tenant.persistence", name = "enabled", havingValue = "true")
public class TenantConfigManagementService {

    private final AiGatewayProperties properties;
    private final TenantConfigQueryService tenantConfigQueryService;
    private final TenantRepository tenantRepository;
    private final TenantAppRepository tenantAppRepository;
    private final TenantApiKeyRepository tenantApiKeyRepository;
    private final TenantModelPolicyRepository tenantModelPolicyRepository;
    private final TenantModelAllowedRepository tenantModelAllowedRepository;
    private final TenantModelMappingRepository tenantModelMappingRepository;
    private final TenantQuotaPolicyRepository tenantQuotaPolicyRepository;
    private final AiModelPriceRepository aiModelPriceRepository;

    public TenantConfigManagementService(AiGatewayProperties properties,
                                         TenantConfigQueryService tenantConfigQueryService,
                                         TenantRepository tenantRepository,
                                         TenantAppRepository tenantAppRepository,
                                         TenantApiKeyRepository tenantApiKeyRepository,
                                         TenantModelPolicyRepository tenantModelPolicyRepository,
                                         TenantModelAllowedRepository tenantModelAllowedRepository,
                                         TenantModelMappingRepository tenantModelMappingRepository,
                                         TenantQuotaPolicyRepository tenantQuotaPolicyRepository,
                                         AiModelPriceRepository aiModelPriceRepository) {
        this.properties = properties;
        this.tenantConfigQueryService = tenantConfigQueryService;
        this.tenantRepository = tenantRepository;
        this.tenantAppRepository = tenantAppRepository;
        this.tenantApiKeyRepository = tenantApiKeyRepository;
        this.tenantModelPolicyRepository = tenantModelPolicyRepository;
        this.tenantModelAllowedRepository = tenantModelAllowedRepository;
        this.tenantModelMappingRepository = tenantModelMappingRepository;
        this.tenantQuotaPolicyRepository = tenantQuotaPolicyRepository;
        this.aiModelPriceRepository = aiModelPriceRepository;
    }

    public Mono<Map<String, Object>> upsertTenant(Map<String, Object> request) {
        assertPersistenceEnabled();
        TenantEntity entity = new TenantEntity();
        entity.setTenantId(required(request, "tenantId"));
        entity.setTenantName(required(request, "tenantName"));
        entity.setTenantStatus(optionalString(request, "tenantStatus", "ACTIVE"));
        entity.setDescription(optionalString(request, "description", null));
        return tenantRepository.findByTenantId(entity.getTenantId())
                .defaultIfEmpty(new TenantEntity())
                .flatMap(existing -> {
                    entity.setId(existing.getId());
                    return tenantRepository.save(entity);
                })
                .then(tenantConfigQueryService.refreshFromDatabaseReactive())
                .thenReturn(Map.of(
                        "tenantId", entity.getTenantId(),
                        "tenantName", entity.getTenantName(),
                        "tenantStatus", entity.getTenantStatus(),
                        "description", Optional.ofNullable(entity.getDescription()).orElse("")
                ));
    }

    public Mono<Map<String, Object>> getTenant(String tenantId) {
        assertPersistenceEnabled();
        return tenantRepository.findByTenantId(tenantId)
                .map(each -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("found", true);
                    result.put("tenantId", each.getTenantId());
                    result.put("tenantName", each.getTenantName());
                    result.put("tenantStatus", each.getTenantStatus());
                    result.put("description", Optional.ofNullable(each.getDescription()).orElse(""));
                    return result;
                })
                .defaultIfEmpty(Map.of("found", false, "tenantId", tenantId));
    }

    public Mono<Map<String, Object>> getTenantApp(String tenantId, String appId) {
        assertPersistenceEnabled();
        return tenantAppRepository.findByTenantIdAndAppId(tenantId, appId)
                .map(each -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("found", true);
                    result.put("tenantId", each.getTenantId());
                    result.put("appId", each.getAppId());
                    result.put("appName", each.getAppName());
                    result.put("appStatus", each.getAppStatus());
                    result.put("description", Optional.ofNullable(each.getDescription()).orElse(""));
                    return result;
                })
                .defaultIfEmpty(Map.of("found", false, "tenantId", tenantId, "appId", appId));
    }

    public Flux<Map<String, Object>> listTenantApps(String tenantId) {
        assertPersistenceEnabled();
        return tenantAppRepository.findAllByTenantId(tenantId)
                .map(each -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("tenantId", each.getTenantId());
                    result.put("appId", each.getAppId());
                    result.put("appName", each.getAppName());
                    result.put("appStatus", each.getAppStatus());
                    result.put("description", Optional.ofNullable(each.getDescription()).orElse(""));
                    return result;
                });
    }

    public Mono<Map<String, Object>> upsertTenantApp(String tenantId, Map<String, Object> request) {
        assertPersistenceEnabled();
        TenantAppEntity entity = new TenantAppEntity();
        entity.setTenantId(tenantId);
        entity.setAppId(required(request, "appId"));
        entity.setAppName(required(request, "appName"));
        entity.setAppStatus(optionalString(request, "appStatus", "ACTIVE"));
        entity.setDescription(optionalString(request, "description", null));
        return tenantAppRepository.findByTenantIdAndAppId(tenantId, entity.getAppId())
                .defaultIfEmpty(new TenantAppEntity())
                .flatMap(existing -> {
                    entity.setId(existing.getId());
                    return tenantAppRepository.save(entity);
                })
                .then(tenantConfigQueryService.refreshFromDatabaseReactive())
                .thenReturn(Map.of(
                        "tenantId", entity.getTenantId(),
                        "appId", entity.getAppId(),
                        "appName", entity.getAppName(),
                        "appStatus", entity.getAppStatus(),
                        "description", Optional.ofNullable(entity.getDescription()).orElse("")
                ));
    }

    public Mono<Map<String, Object>> upsertApiKey(Map<String, Object> request) {
        assertPersistenceEnabled();
        TenantApiKeyEntity entity = new TenantApiKeyEntity();
        entity.setTenantId(required(request, "tenantId"));
        entity.setAppId(required(request, "appId"));
        entity.setKeyId(required(request, "keyId"));
        entity.setApiKey(required(request, "apiKey"));
        entity.setEnabled(optionalBoolean(request, "enabled", true));
        entity.setExpiresAt(optionalInstant(request, "expiresAt"));
        return tenantApiKeyRepository.findByApiKey(entity.getApiKey())
                .defaultIfEmpty(new TenantApiKeyEntity())
                .flatMap(existing -> {
                    entity.setId(existing.getId());
                    return tenantApiKeyRepository.save(entity);
                })
                .flatMap(saved -> tenantConfigQueryService.refreshFromDatabaseReactive().thenReturn(Map.of(
                        "tenantId", saved.getTenantId(),
                        "appId", saved.getAppId(),
                        "keyId", saved.getKeyId(),
                        "enabled", Boolean.TRUE.equals(saved.getEnabled())
                )));
    }

    public Mono<Map<String, Object>> upsertModelPolicy(String tenantId, Map<String, Object> request) {
        assertPersistenceEnabled();
        TenantModelPolicyEntity entity = new TenantModelPolicyEntity();
        entity.setTenantId(tenantId);
        entity.setEnabled(optionalBoolean(request, "enabled", true));
        entity.setDefaultModelAlias(optionalString(request, "defaultModelAlias", "default"));
        entity.setDefaultModel(optionalString(request, "defaultModel", null));

        List<String> allowedModels = optionalStringList(request, "allowedModels");
        Map<String, String> modelMappings = optionalStringMap(request, "modelMappings");

        return tenantModelPolicyRepository.findByTenantId(tenantId)
                .defaultIfEmpty(new TenantModelPolicyEntity())
                .flatMap(existing -> {
                    entity.setId(existing.getId());
                    return tenantModelPolicyRepository.save(entity);
                })
                .flatMap(saved -> tenantModelAllowedRepository.deleteAllByTenantId(tenantId)
                        .then(tenantModelMappingRepository.deleteAllByTenantId(tenantId))
                        .thenMany(Flux.fromIterable(allowedModels)
                                .flatMap(each -> {
                                    TenantModelAllowedEntity allowedEntity = new TenantModelAllowedEntity();
                                    allowedEntity.setTenantId(tenantId);
                                    allowedEntity.setAllowedModel(each);
                                    return tenantModelAllowedRepository.save(allowedEntity);
                                }))
                        .thenMany(Flux.fromIterable(modelMappings.entrySet())
                                .flatMap(each -> {
                                    TenantModelMappingEntity mappingEntity = new TenantModelMappingEntity();
                                    mappingEntity.setTenantId(tenantId);
                                    mappingEntity.setRequestModel(each.getKey());
                                    mappingEntity.setProviderModel(each.getValue());
                                    return tenantModelMappingRepository.save(mappingEntity);
                                }))
                        .then(tenantConfigQueryService.refreshFromDatabaseReactive())
                        .thenReturn(Map.of(
                                "tenantId", tenantId,
                                "enabled", Boolean.TRUE.equals(saved.getEnabled()),
                                "allowedModels", allowedModels,
                                "modelMappings", modelMappings,
                                "defaultModelAlias", saved.getDefaultModelAlias(),
                                "defaultModel", Optional.ofNullable(saved.getDefaultModel()).orElse("")
                        )));
    }

    public Mono<Map<String, Object>> upsertQuotaPolicy(String tenantId, Map<String, Object> request) {
        assertPersistenceEnabled();
        TenantQuotaPolicyEntity entity = new TenantQuotaPolicyEntity();
        entity.setTenantId(tenantId);
        entity.setEnabled(optionalBoolean(request, "enabled", true));
        entity.setTokenQuotaPerMinute(optionalLong(request, "tokenQuotaPerMinute"));
        entity.setTokenQuotaPerDay(optionalLong(request, "tokenQuotaPerDay"));
        entity.setTokenQuotaPerMonth(optionalLong(request, "tokenQuotaPerMonth"));
        return tenantQuotaPolicyRepository.findByTenantId(tenantId)
                .defaultIfEmpty(new TenantQuotaPolicyEntity())
                .flatMap(existing -> {
                    entity.setId(existing.getId());
                    return tenantQuotaPolicyRepository.save(entity);
                })
                .flatMap(saved -> tenantConfigQueryService.refreshFromDatabaseReactive().thenReturn(Map.of(
                        "tenantId", tenantId,
                        "enabled", Boolean.TRUE.equals(saved.getEnabled()),
                        "tokenQuotaPerMinute", Optional.ofNullable(saved.getTokenQuotaPerMinute()).orElse(0L),
                        "tokenQuotaPerDay", Optional.ofNullable(saved.getTokenQuotaPerDay()).orElse(0L),
                        "tokenQuotaPerMonth", Optional.ofNullable(saved.getTokenQuotaPerMonth()).orElse(0L)
                )));
    }

    public Mono<Map<String, Object>> upsertModelPrice(String model, Map<String, Object> request) {
        assertPersistenceEnabled();
        AiModelPriceEntity entity = new AiModelPriceEntity();
        entity.setModel(model);
        entity.setInputPer1k(optionalDouble(request, "inputPer1k", 0D));
        entity.setOutputPer1k(optionalDouble(request, "outputPer1k", 0D));
        entity.setEnabled(optionalBoolean(request, "enabled", true));
        return aiModelPriceRepository.findByModel(model)
                .defaultIfEmpty(new AiModelPriceEntity())
                .flatMap(existing -> {
                    entity.setId(existing.getId());
                    return aiModelPriceRepository.save(entity);
                })
                .flatMap(saved -> tenantConfigQueryService.refreshFromDatabaseReactive().thenReturn(Map.of(
                        "model", saved.getModel(),
                        "inputPer1k", Optional.ofNullable(saved.getInputPer1k()).orElse(0D),
                        "outputPer1k", Optional.ofNullable(saved.getOutputPer1k()).orElse(0D),
                        "enabled", Boolean.TRUE.equals(saved.getEnabled())
                )));
    }

    public Mono<Map<String, Object>> deleteTenant(String tenantId) {
        assertPersistenceEnabled();
        return tenantApiKeyRepository.deleteAllByTenantId(tenantId)
                .then(tenantModelAllowedRepository.deleteAllByTenantId(tenantId))
                .then(tenantModelMappingRepository.deleteAllByTenantId(tenantId))
                .then(tenantQuotaPolicyRepository.deleteByTenantId(tenantId))
                .then(tenantModelPolicyRepository.deleteByTenantId(tenantId))
                .then(tenantAppRepository.deleteAllByTenantId(tenantId))
                .then(tenantRepository.deleteByTenantId(tenantId))
                .then(tenantConfigQueryService.refreshFromDatabaseReactive())
                .thenReturn(Map.of("tenantId", tenantId, "deleted", true));
    }

    public Mono<Map<String, Object>> deleteTenantApp(String tenantId, String appId) {
        assertPersistenceEnabled();
        return tenantAppRepository.deleteByTenantIdAndAppId(tenantId, appId)
                .then(tenantConfigQueryService.refreshFromDatabaseReactive())
                .thenReturn(Map.of("tenantId", tenantId, "appId", appId, "deleted", true));
    }

    public Mono<Map<String, Object>> deleteApiKey(String apiKey) {
        assertPersistenceEnabled();
        return tenantApiKeyRepository.deleteByApiKey(apiKey)
                .then(tenantConfigQueryService.refreshFromDatabaseReactive())
                .thenReturn(Map.of("apiKey", apiKey, "deleted", true));
    }

    public Mono<Map<String, Object>> deleteModelPolicy(String tenantId) {
        assertPersistenceEnabled();
        return tenantModelAllowedRepository.deleteAllByTenantId(tenantId)
                .then(tenantModelMappingRepository.deleteAllByTenantId(tenantId))
                .then(tenantModelPolicyRepository.deleteByTenantId(tenantId))
                .then(tenantConfigQueryService.refreshFromDatabaseReactive())
                .thenReturn(Map.of("tenantId", tenantId, "deleted", true));
    }

    public Mono<Map<String, Object>> deleteQuotaPolicy(String tenantId) {
        assertPersistenceEnabled();
        return tenantQuotaPolicyRepository.deleteByTenantId(tenantId)
                .then(tenantConfigQueryService.refreshFromDatabaseReactive())
                .thenReturn(Map.of("tenantId", tenantId, "deleted", true));
    }

    public Mono<Map<String, Object>> deleteModelPrice(String model) {
        assertPersistenceEnabled();
        return aiModelPriceRepository.deleteByModel(model)
                .then(tenantConfigQueryService.refreshFromDatabaseReactive())
                .thenReturn(Map.of("model", model, "deleted", true));
    }

    private void assertPersistenceEnabled() {
        if (!properties.getTenant().getPersistence().isEnabled()) {
            throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, "当前未启用 tenant DB 持久化");
        }
    }

    private String required(Map<String, Object> request, String key) {
        String value = optionalString(request, key, null);
        if (!StringUtils.hasText(value)) {
            throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, key + " 不能为空");
        }
        return value;
    }

    private String optionalString(Map<String, Object> request, String key, String defaultValue) {
        Object value = request.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? defaultValue : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> optionalStringMap(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (!(value instanceof Map<?, ?> mapValue)) {
            return Map.of();
        }
        return mapValue.entrySet().stream()
                .filter(each -> each.getKey() != null && each.getValue() != null)
                .collect(java.util.stream.Collectors.toMap(
                        each -> String.valueOf(each.getKey()).trim(),
                        each -> String.valueOf(each.getValue()).trim(),
                        (left, right) -> right,
                        java.util.LinkedHashMap::new
                ));
    }

    @SuppressWarnings("unchecked")
    private List<String> optionalStringList(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (!(value instanceof List<?> listValue)) {
            return List.of();
        }
        return listValue.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(each -> !each.isBlank())
                .distinct()
                .toList();
    }

    private Boolean optionalBoolean(Map<String, Object> request, String key, boolean defaultValue) {
        Object value = request.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Long optionalLong(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Double optionalDouble(Map<String, Object> request, String key, double defaultValue) {
        Object value = request.get(key);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return defaultValue;
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private Instant optionalInstant(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        return Instant.parse(String.valueOf(value));
    }
}
