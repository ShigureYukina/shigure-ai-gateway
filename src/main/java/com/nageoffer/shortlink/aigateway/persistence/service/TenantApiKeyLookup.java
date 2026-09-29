package com.nageoffer.shortlink.aigateway.persistence.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTenantProperties;
import com.nageoffer.shortlink.aigateway.crypto.AesGcmSecretCipher;
import com.nageoffer.shortlink.aigateway.crypto.SecretCipher;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantApiKeyEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantApiKeyRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户密钥域：平台 API Key -> 租户身份。
 * <p>
 * 边界：只负责 {@code tenant_api_key} 一张表。它决定"这个 Key 是谁"，
 * 是整条请求链路的入口，因此查询不做任何额外判断——启用与否由 {@code ApiKeyAuthService} 自己决定。
 */
@Slf4j
public class TenantApiKeyLookup {

    private final AiGatewayProperties properties;

    private final TenantApiKeyRepository repository;

    private final SecretCipher secretCipher;

    /**
     * 内存快照，key 是<b>解密后的明文 Key</b>。
     * <p>
     * 为什么缓存键不改成 {@code api_key_hash}：这个 Map 的 value（{@link AiGatewayTenantProperties.TenantApiKeyCredential}）
     * 本来就持有明文 —— {@code ApiKeyAuthService.resolveCredentialKeyId} 还要拿它跟请求头的 Key 比。
     * 既然明文无论如何都在进程内存里，把 key 换成哈希不增加任何保护，却会引入
     * "hash 未回填的行查不到"与"主密钥轮换后哈希失配"两个新的空档。
     * 哈希的用武之地是<b>库上的唯一键与按 Key 查行</b>，见 {@code crypto.SecretHasher}。
     */
    private final Map<String, AiGatewayTenantProperties.TenantApiKeyCredential> cache = new ConcurrentHashMap<>();

    public TenantApiKeyLookup(AiGatewayProperties properties, TenantApiKeyRepository repository) {
        this(properties, repository, AesGcmSecretCipher.disabled());
    }

    public TenantApiKeyLookup(AiGatewayProperties properties,
                              TenantApiKeyRepository repository,
                              SecretCipher secretCipher) {
        this.properties = properties;
        this.repository = repository;
        this.secretCipher = secretCipher;
    }

    /**
     * 本域的仓库是否已装配。未装配时加载/应用整体跳过。
     */
    public boolean ready() {
        return repository != null;
    }

    public Mono<List<TenantApiKeyEntity>> load() {
        if (!ready()) {
            return Mono.empty();
        }
        return repository.findAll().collectList();
    }

    /**
     * 整体替换快照。
     * <p>
     * 单行解密失败只跳过那一行：一条坏行（主密钥换过、行被改坏）不该让<b>所有</b>租户都鉴权失败。
     * 跳过是"响亮地失败"—— 日志有 error，那个 Key 会稳定地 401，而不是变成某个古怪的 Key 去访问上游。
     */
    public void apply(List<TenantApiKeyEntity> entities) {
        cache.clear();
        entities.forEach(entity -> {
            String plainKey;
            try {
                plainKey = secretCipher.decrypt(entity.getApiKey());
            } catch (RuntimeException ex) {
                log.error("skipping tenant api key row id={} tenantId={}: {}",
                        entity.getId(), entity.getTenantId(), ex.getMessage());
                return;
            }
            cache.put(plainKey, toCredential(entity, plainKey));
        });
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    public Optional<AiGatewayTenantProperties.TenantApiKeyCredential> find(String apiKey) {
        return DomainLookupSupport.preferSnapshot(properties, cache.get(apiKey),
                () -> properties.getTenant().getApiKeys().values().stream()
                        .filter(each -> apiKey.equals(each.getApiKey()))
                        .findFirst()
                        .orElse(null));
    }

    private AiGatewayTenantProperties.TenantApiKeyCredential toCredential(TenantApiKeyEntity entity, String plainKey) {
        AiGatewayTenantProperties.TenantApiKeyCredential credential = new AiGatewayTenantProperties.TenantApiKeyCredential();
        credential.setApiKey(plainKey);
        credential.setTenantId(entity.getTenantId());
        credential.setAppId(entity.getAppId());
        credential.setKeyId(entity.getKeyId());
        credential.setEnabled(Boolean.TRUE.equals(entity.getEnabled()));
        credential.setExpiresAt(entity.getExpiresAt());
        return credential;
    }
}
