package com.nageoffer.shortlink.aigateway.persistence.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.crypto.AesGcmSecretCipher;
import com.nageoffer.shortlink.aigateway.crypto.SecretCipher;
import com.nageoffer.shortlink.aigateway.crypto.SecretHasher;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.alibaba.fastjson2.JSON;
import com.nageoffer.shortlink.aigateway.persistence.entity.AiModelPriceEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderCredentialEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantAppEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantApiKeyEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelAllowedEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelMappingEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelPolicyEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantQuotaPolicyEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.AiModelPriceRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderCredentialRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantAppRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantApiKeyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelAllowedRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelMappingRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelPolicyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantQuotaPolicyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
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
    private final ProviderCredentialRepository providerCredentialRepository;

    private final SecretCipher secretCipher;

    private final SecretHasher secretHasher;

    /**
     * 不加密的装配：给单测与"接库但没配主密钥"的场景用，行为与加固前完全一致。
     */
    public TenantConfigManagementService(AiGatewayProperties properties,
                                         TenantConfigQueryService tenantConfigQueryService,
                                         TenantRepository tenantRepository,
                                         TenantAppRepository tenantAppRepository,
                                         TenantApiKeyRepository tenantApiKeyRepository,
                                         TenantModelPolicyRepository tenantModelPolicyRepository,
                                         TenantModelAllowedRepository tenantModelAllowedRepository,
                                         TenantModelMappingRepository tenantModelMappingRepository,
                                         TenantQuotaPolicyRepository tenantQuotaPolicyRepository,
                                         AiModelPriceRepository aiModelPriceRepository,
                                         ProviderCredentialRepository providerCredentialRepository) {
        this(properties, tenantConfigQueryService, tenantRepository, tenantAppRepository, tenantApiKeyRepository,
                tenantModelPolicyRepository, tenantModelAllowedRepository, tenantModelMappingRepository,
                tenantQuotaPolicyRepository, aiModelPriceRepository, providerCredentialRepository,
                AesGcmSecretCipher.disabled(), SecretHasher.disabled());
    }

    @Autowired
    public TenantConfigManagementService(AiGatewayProperties properties,
                                         TenantConfigQueryService tenantConfigQueryService,
                                         TenantRepository tenantRepository,
                                         TenantAppRepository tenantAppRepository,
                                         TenantApiKeyRepository tenantApiKeyRepository,
                                         TenantModelPolicyRepository tenantModelPolicyRepository,
                                         TenantModelAllowedRepository tenantModelAllowedRepository,
                                         TenantModelMappingRepository tenantModelMappingRepository,
                                         TenantQuotaPolicyRepository tenantQuotaPolicyRepository,
                                         AiModelPriceRepository aiModelPriceRepository,
                                         ProviderCredentialRepository providerCredentialRepository,
                                         SecretCipher secretCipher,
                                         SecretHasher secretHasher) {
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
        this.providerCredentialRepository = providerCredentialRepository;
        this.secretCipher = secretCipher;
        this.secretHasher = secretHasher;
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

    /**
     * 新增或更新平台 API Key。
     * <p>
     * 落库的是密文 + 确定性哈希（见 {@code crypto.SecretCipher} / {@code crypto.SecretHasher}）；
     * 没配主密钥时两者都是原样透传 / 空，行为与加固前完全一致。
     */
    public Mono<Map<String, Object>> upsertApiKey(Map<String, Object> request) {
        assertPersistenceEnabled();
        String rawApiKey = required(request, "apiKey");
        TenantApiKeyEntity entity = new TenantApiKeyEntity();
        entity.setTenantId(required(request, "tenantId"));
        entity.setAppId(required(request, "appId"));
        entity.setKeyId(required(request, "keyId"));
        entity.setApiKey(secretCipher.encrypt(rawApiKey));
        entity.setApiKeyHash(secretHasher.hmac(rawApiKey));
        entity.setEnabled(optionalBoolean(request, "enabled", true));
        entity.setExpiresAt(optionalInstant(request, "expiresAt"));
        return findApiKeyRow(rawApiKey)
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

    /**
     * 新增或更新上游凭证。
     * <p>
     * {@code tenantId} 为空时写入平台级凭证（伪租户 {@code *}）；
     * 指定 tenantId 时写入该租户的 BYOK 凭证，运行期优先于平台级生效。
     */
    public Mono<Map<String, Object>> upsertProviderCredential(String tenantId, String provider, Map<String, Object> request) {
        assertPersistenceEnabled();
        String scopeTenant = StringUtils.hasText(tenantId) ? tenantId.trim() : ProviderCredentialEntity.GLOBAL_TENANT_ID;
        ProviderCredentialEntity entity = new ProviderCredentialEntity();
        entity.setTenantId(scopeTenant);
        entity.setProvider(provider);
        entity.setApiKey(secretCipher.encrypt(required(request, "apiKey")));
        entity.setAuthHeader(optionalString(request, "authHeader", "Authorization"));
        entity.setAuthScheme(optionalString(request, "authScheme", "Bearer"));
        entity.setExtraHeaders(serializeExtraHeaders(request.get("extraHeaders")));
        entity.setEnabled(optionalBoolean(request, "enabled", true));
        return providerCredentialRepository.findByTenantIdAndProvider(scopeTenant, provider)
                .defaultIfEmpty(new ProviderCredentialEntity())
                .flatMap(existing -> {
                    entity.setId(existing.getId());
                    return providerCredentialRepository.save(entity);
                })
                .flatMap(saved -> tenantConfigQueryService.refreshFromDatabaseReactive().thenReturn(Map.of(
                        "tenantId", saved.getTenantId(),
                        "provider", saved.getProvider(),
                        "authHeader", Optional.ofNullable(saved.getAuthHeader()).orElse(HttpHeaders.AUTHORIZATION),
                        "authScheme", Optional.ofNullable(saved.getAuthScheme()).orElse(""),
                        "apiKeyMasked", maskApiKey(saved.getApiKey()),
                        "enabled", Boolean.TRUE.equals(saved.getEnabled())
                )));
    }

    public Mono<Map<String, Object>> getProviderCredential(String tenantId, String provider) {
        assertPersistenceEnabled();
        String scopeTenant = StringUtils.hasText(tenantId) ? tenantId.trim() : ProviderCredentialEntity.GLOBAL_TENANT_ID;
        return providerCredentialRepository.findByTenantIdAndProvider(scopeTenant, provider)
                .map(each -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("found", true);
                    result.put("tenantId", each.getTenantId());
                    result.put("provider", each.getProvider());
                    result.put("authHeader", Optional.ofNullable(each.getAuthHeader()).orElse(HttpHeaders.AUTHORIZATION));
                    result.put("authScheme", Optional.ofNullable(each.getAuthScheme()).orElse(""));
                    result.put("apiKeyMasked", maskApiKey(each.getApiKey()));
                    result.put("enabled", Boolean.TRUE.equals(each.getEnabled()));
                    return result;
                })
                .defaultIfEmpty(Map.of("found", false, "tenantId", scopeTenant, "provider", provider));
    }

    public Mono<Map<String, Object>> deleteProviderCredential(String tenantId, String provider) {
        assertPersistenceEnabled();
        String scopeTenant = StringUtils.hasText(tenantId) ? tenantId.trim() : ProviderCredentialEntity.GLOBAL_TENANT_ID;
        return providerCredentialRepository.deleteByTenantIdAndProvider(scopeTenant, provider)
                .then(tenantConfigQueryService.refreshFromDatabaseReactive())
                .thenReturn(Map.of("tenantId", scopeTenant, "provider", provider, "deleted", true));
    }

    /**
     * 只回显凭证尾部，避免管理面把上游 Key 完整吐出。
     * <p>
     * 入参是<b>库里的存值</b>（可能是密文），所以先解密再掩码 —— 否则回显的是密文的前 4 位，
     * 既没有排障价值，还把一个已经加密的值又漏了一次。
     * 解密失败不抛：这是展示路径，一行坏数据不该让整页凭证都看不了。
     */
    private String maskApiKey(String storedApiKey) {
        String apiKey;
        try {
            apiKey = secretCipher.decrypt(storedApiKey);
        } catch (RuntimeException ex) {
            log.error("failed to decrypt stored api key for display: {}", ex.getMessage());
            return "<解密失败>";
        }
        if (!StringUtils.hasText(apiKey)) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * 按明文 Key 找行：配了主密钥就按哈希查，没配就按明文查。
     * <p>
     * 哈希查不到时再回退一次明文查：这是给"加密刚开启、{@code SecretMigrationRunner} 还没回填完"
     * 那个窗口兜底的 —— 少了这一步，重新提交一个未回填的 Key 会插出新行，
     * 直接撞上 {@code uk_tenant_api_key_key_id} 报唯一键冲突。
     */
    private Mono<TenantApiKeyEntity> findApiKeyRow(String rawApiKey) {
        String hash = secretHasher.hmac(rawApiKey);
        if (hash == null) {
            return tenantApiKeyRepository.findByApiKey(rawApiKey);
        }
        return tenantApiKeyRepository.findByApiKeyHash(hash)
                .switchIfEmpty(Mono.defer(() -> tenantApiKeyRepository.findByApiKey(rawApiKey)));
    }

    private String serializeExtraHeaders(Object value) {
        if (!(value instanceof Map<?, ?> mapValue) || mapValue.isEmpty()) {
            return null;
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        mapValue.forEach((key, headerValue) -> {
            if (key != null && headerValue != null && StringUtils.hasText(String.valueOf(headerValue))) {
                normalized.put(String.valueOf(key).trim(), String.valueOf(headerValue).trim());
            }
        });
        return normalized.isEmpty() ? null : JSON.toJSONString(normalized);
    }

    public Mono<Map<String, Object>> deleteTenant(String tenantId) {
        assertPersistenceEnabled();
        return tenantApiKeyRepository.deleteAllByTenantId(tenantId)
                .then(providerCredentialRepository.deleteAllByTenantId(tenantId))
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

    /**
     * 删除平台 API Key。
     * <p>
     * 响应<b>不再回显被删的 Key</b>：这个 Map 会进响应体、日志与审计明细，
     * 回显等于给"删掉的密钥"又发了一张副本。
     */
    public Mono<Map<String, Object>> deleteApiKey(String apiKey) {
        assertPersistenceEnabled();
        return deleteApiKeyRow(apiKey)
                .then(tenantConfigQueryService.refreshFromDatabaseReactive())
                .thenReturn(Map.of("deleted", true));
    }

    /**
     * 按哈希删一次，再按明文删一次：前者覆盖已回填的行，后者覆盖迁移窗口内还没回填的行。
     * 两边都删不到也不算失败（幂等）。
     */
    private Mono<Void> deleteApiKeyRow(String rawApiKey) {
        String hash = secretHasher.hmac(rawApiKey);
        if (hash == null) {
            return tenantApiKeyRepository.deleteByApiKey(rawApiKey);
        }
        return tenantApiKeyRepository.deleteByApiKeyHash(hash)
                .then(tenantApiKeyRepository.deleteByApiKey(rawApiKey));
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
