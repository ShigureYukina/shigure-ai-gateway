package com.nageoffer.shortlink.aigateway.persistence.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.crypto.AesGcmSecretCipher;
import com.nageoffer.shortlink.aigateway.crypto.SecretCipher;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderCredentialEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderCredentialKeyEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderCredentialKeyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderCredentialRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上游凭证域：平台级 / 租户 BYOK 凭证，以及同渠道多 Key 池。
 * <p>
 * 边界：{@code provider_credential} 与 {@code provider_credential_key} 两张表。它们都回答"用什么 Key 打上游"，
 * 区别是前者是单 Key 凭证（含鉴权头、附加头），后者是同渠道的多 Key 列表。
 * <p>
 * 两个关键语义：
 * <ul>
 *   <li>平台级凭证以 {@link ProviderCredentialEntity#GLOBAL_TENANT_ID} 为租户标记，
 *       因此"查平台凭证"与"查某租户 BYOK"走同一张表但键不同，不能互相命中；</li>
 *   <li>Key 池<b>单独加载</b>：它是后加的表，读失败不能把已经加载好的主快照一起拖挂。</li>
 * </ul>
 */
@Slf4j
public class ProviderCredentialLookup {

    private static final String CREDENTIAL_KEY_SEPARATOR = "|";

    private final AiGatewayProperties properties;

    private final ProviderCredentialRepository repository;

    private final ProviderCredentialKeyRepository keyRepository;

    private final SecretCipher secretCipher;

    private final Map<String, AiGatewayProperties.ProviderCredential> credentialCache = new ConcurrentHashMap<>();

    /**
     * Key 池快照，key 为 {@code tenantId|provider}。
     */
    private final Map<String, List<AiGatewayProperties.ProviderApiKey>> keyPoolCache = new ConcurrentHashMap<>();

    public ProviderCredentialLookup(AiGatewayProperties properties,
                                   ProviderCredentialRepository repository,
                                   ProviderCredentialKeyRepository keyRepository) {
        this(properties, repository, keyRepository, AesGcmSecretCipher.disabled());
    }

    public ProviderCredentialLookup(AiGatewayProperties properties,
                                   ProviderCredentialRepository repository,
                                   ProviderCredentialKeyRepository keyRepository,
                                   SecretCipher secretCipher) {
        this.properties = properties;
        this.repository = repository;
        this.keyRepository = keyRepository;
        this.secretCipher = secretCipher;
    }

    /**
     * 主快照是否可加载。Key 池不参与这个判断——见类注释。
     */
    public boolean ready() {
        return repository != null;
    }

    public Mono<List<ProviderCredentialEntity>> load() {
        if (!ready()) {
            return Mono.empty();
        }
        return repository.findAll().collectList();
    }

    /**
     * 整体替换凭证快照：只有 enabled 的行生效。
     * <p>
     * 单行解密失败只跳过那一行：一条坏行不该让<b>所有</b>渠道一起失效。
     * 跳过之后该渠道会以"未配置凭证"落地，比拿一段解不开的串去打上游清楚得多。
     */
    public void apply(List<ProviderCredentialEntity> entities) {
        credentialCache.clear();
        entities.stream()
                .filter(each -> Boolean.TRUE.equals(each.getEnabled()))
                .forEach(each -> {
                    AiGatewayProperties.ProviderCredential credential;
                    try {
                        credential = toProviderCredential(each);
                    } catch (RuntimeException ex) {
                        log.error("skipping provider credential row id={} tenantId={} provider={}: {}",
                                each.getId(), each.getTenantId(), each.getProvider(), ex.getMessage());
                        return;
                    }
                    credentialCache.put(credentialKey(each.getTenantId(), each.getProvider()), credential);
                });
    }

    /**
     * 单独加载 Key 池。失败只降级为"Key 池不可用"，主快照不受影响。
     */
    public Mono<Void> loadKeyPool() {
        if (keyRepository == null) {
            return Mono.empty();
        }
        return keyRepository.findAll()
                .collectList()
                .doOnNext(this::applyKeyPool)
                .then()
                .onErrorResume(ex -> {
                    log.warn("provider key pool unavailable, fallback to yml api-keys: {}", ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 清空两个缓存。Key 池与主快照同属一个域，快照不可信时两者都不能留。
     */
    public void clear() {
        credentialCache.clear();
        keyPoolCache.clear();
    }

    public int size() {
        return credentialCache.size();
    }

    /**
     * 租户 BYOK 凭证。
     */
    public Optional<AiGatewayProperties.ProviderCredential> find(String tenantId, String provider) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(provider)) {
            return Optional.empty();
        }
        return DomainLookupSupport.preferSnapshot(properties, credentialCache.get(credentialKey(tenantId, provider)),
                () -> {
                    Map<String, AiGatewayProperties.ProviderCredential> tenantScoped =
                            properties.getTenant().getProviderCredentials().get(tenantId);
                    return tenantScoped == null ? null : tenantScoped.get(provider);
                });
    }

    /**
     * 平台级凭证。与租户 BYOK 共用一张表，靠 {@code GLOBAL_TENANT_ID} 区分。
     */
    public Optional<AiGatewayProperties.ProviderCredential> findGlobal(String provider) {
        if (!StringUtils.hasText(provider)) {
            return Optional.empty();
        }
        return DomainLookupSupport.preferSnapshot(properties,
                credentialCache.get(credentialKey(ProviderCredentialEntity.GLOBAL_TENANT_ID, provider)),
                () -> properties.getUpstream().getProviderCredentials().get(provider));
    }

    /**
     * 渠道 Key 池：DB 优先，未配置时回退到 yml 里的 {@code provider-credentials.<provider>.api-keys}。
     * <p>
     * 两者都为空时返回空列表，调用方回落到单 Key 的 {@code apiKey}。
     */
    public List<AiGatewayProperties.ProviderApiKey> findKeys(String tenantId, String provider) {
        if (!StringUtils.hasText(provider)) {
            return List.of();
        }
        if (DomainLookupSupport.snapshotEnabled(properties)) {
            List<AiGatewayProperties.ProviderApiKey> fromDatabase = keyPoolCache.get(credentialKey(tenantId, provider));
            if (fromDatabase != null && !fromDatabase.isEmpty()) {
                return fromDatabase;
            }
        }
        AiGatewayProperties.ProviderCredential credential = properties.getUpstream().getProviderCredentials().get(provider);
        if (credential != null && credential.getApiKeys() != null && !credential.getApiKeys().isEmpty()) {
            return credential.getApiKeys();
        }
        return List.of();
    }

    /**
     * 按 {@code tenantId|provider} 归组，并丢掉不可用的 Key：禁用的、缺 apiKey 的、解不开的。
     * <p>
     * 未填权重按 1 处理，避免下游加权随机拿到 null。
     * <p>
     * 判空放在<b>解密之后</b>：密文形态下 {@code enc:v1:} 的密文本身非空，
     * 但解密结果可能是空串（虽然 encrypt 不会为空串产出密文，历史脏数据可能）。
     */
    private void applyKeyPool(List<ProviderCredentialKeyEntity> keys) {
        keyPoolCache.clear();
        Map<String, List<AiGatewayProperties.ProviderApiKey>> grouped = new LinkedHashMap<>();
        for (ProviderCredentialKeyEntity key : keys) {
            if (!Boolean.TRUE.equals(key.getEnabled()) || !StringUtils.hasText(key.getApiKey())) {
                continue;
            }
            String plainKey;
            try {
                plainKey = secretCipher.decrypt(key.getApiKey());
            } catch (RuntimeException ex) {
                log.error("skipping provider key pool row id={} tenantId={} provider={} keyId={}: {}",
                        key.getId(), key.getTenantId(), key.getProvider(), key.getKeyId(), ex.getMessage());
                continue;
            }
            if (!StringUtils.hasText(plainKey)) {
                continue;
            }
            AiGatewayProperties.ProviderApiKey apiKey = new AiGatewayProperties.ProviderApiKey();
            apiKey.setKeyId(key.getKeyId());
            apiKey.setApiKey(plainKey);
            apiKey.setWeight(key.getWeight() == null ? 1 : key.getWeight());
            apiKey.setEnabled(true);
            grouped.computeIfAbsent(credentialKey(key.getTenantId(), key.getProvider()), ignored -> new ArrayList<>())
                    .add(apiKey);
        }
        keyPoolCache.putAll(grouped);
    }

    private String credentialKey(String tenantId, String provider) {
        return tenantId + CREDENTIAL_KEY_SEPARATOR + provider;
    }

    private AiGatewayProperties.ProviderCredential toProviderCredential(ProviderCredentialEntity entity) {
        AiGatewayProperties.ProviderCredential credential = new AiGatewayProperties.ProviderCredential();
        credential.setApiKey(secretCipher.decrypt(entity.getApiKey()));
        credential.setAuthHeader(entity.getAuthHeader());
        credential.setAuthScheme(entity.getAuthScheme());
        credential.setExtraHeaders(parseExtraHeaders(entity.getExtraHeaders()));
        credential.setEnabled(Boolean.TRUE.equals(entity.getEnabled()));
        return credential;
    }

    /**
     * 附加头是运维手写的 JSON，脏数据不能让整条凭证失效——宁可不带附加头，也不能把渠道搞挂。
     */
    private Map<String, String> parseExtraHeaders(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new HashMap<>();
        }
        try {
            JSONObject jsonObject = JSON.parseObject(raw);
            if (jsonObject == null || jsonObject.isEmpty()) {
                return new HashMap<>();
            }
            Map<String, String> result = new HashMap<>();
            jsonObject.forEach((key, value) -> {
                if (StringUtils.hasText(key) && value != null && StringUtils.hasText(String.valueOf(value))) {
                    result.put(key, String.valueOf(value));
                }
            });
            return result;
        } catch (Exception ex) {
            log.warn("invalid extra_headers json in provider_credential, ignored: {}", raw);
            return new HashMap<>();
        }
    }
}
