package com.nageoffer.shortlink.aigateway.security;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewaySecurityProperties;
import com.nageoffer.shortlink.aigateway.crypto.AesGcmSecretCipher;
import com.nageoffer.shortlink.aigateway.crypto.SecretCipher;
import com.nageoffer.shortlink.aigateway.crypto.SecretHasher;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsKeys;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderCredentialEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderCredentialKeyEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantApiKeyEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderCredentialKeyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderCredentialRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantApiKeyRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 存量明文的密钥迁移：加密开启后把还没有 {@code enc:v1:} 前缀的行就地改写。
 * <p>
 * <b>幂等</b>：判据就是"这行是不是已经加密 / 有没有 hash"，加密之后的下一次启动自然不再命中。
 * 所以它可以在每次启动时无条件跑一遍，不需要"迁移版本表"这类额外状态。
 * <p>
 * <b>失败不阻断启动</b>：读路径同时兼容明文与密文（见 {@code AesGcmSecretCipher.decrypt}），
 * 所以迁移只做到一半也能正常服务 —— 没迁到的行退化为明文，下次启动接着迁。
 * 反过来"迁不完就不让起"会让一次数据库抖动直接变成一次线上故障。
 * <p>
 * <b>多实例</b>：用 Redis {@code SET NX PX 60s} 选一个实例干活。抢不到就跳过——
 * 即使锁失效导致两个实例同时迁，写入的哈希是确定性的、值等价，也不会写坏数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecretMigrationRunner {

    /**
     * 复用指标键的公共前缀（见 {@code RuntimeConfigKeys} 的同类做法），
     * 只管键名与生命周期，不合并读写客户端。
     */
    private static final String LOCK_KEY = AiGatewayMetricsKeys.PREFIX + "secret-migration:lock";

    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private final AiGatewayProperties properties;

    private final SecretCipher secretCipher;

    private final SecretHasher secretHasher;

    private final PasswordEncoderSupport passwordEncoderSupport;

    private final StringRedisTemplate stringRedisTemplate;

    private final Environment environment;

    private final ObjectProvider<TenantApiKeyRepository> tenantApiKeyRepository;

    private final ObjectProvider<ProviderCredentialRepository> providerCredentialRepository;

    private final ObjectProvider<ProviderCredentialKeyRepository> providerCredentialKeyRepository;

    @PostConstruct
    void migrate() {
        if (!properties.getTenant().getPersistence().isEnabled()) {
            return;
        }
        if (!secretCipher.enabled()) {
            // dev 的合法状态：没有主密钥就没有"待加密"这回事，也不能生成查找哈希
            log.info("AI_GATEWAY_MASTER_KEY absent, secret migration skipped (plaintext values stay as-is)");
            return;
        }
        TenantApiKeyRepository apiKeys = tenantApiKeyRepository.getIfAvailable();
        ProviderCredentialRepository credentials = providerCredentialRepository.getIfAvailable();
        ProviderCredentialKeyRepository keyPool = providerCredentialKeyRepository.getIfAvailable();
        if (apiKeys == null && credentials == null && keyPool == null) {
            return;
        }

        if (!acquireLock()) {
            log.info("another instance holds the secret migration lock, skipping in this instance");
            return;
        }
        try {
            int migrated = migrateTenantApiKeys(apiKeys)
                    + migrateProviderCredentials(credentials)
                    + migrateProviderKeyPool(keyPool);
            if (migrated > 0) {
                log.info("secret migration finished, rows rewritten with enc:v1: ciphertext = {}", migrated);
            }
        } catch (Exception ex) {
            log.error("secret migration aborted, remaining plaintext rows keep working until the next startup", ex);
        } finally {
            warnPlaintextConsolePasswords();
            releaseLock();
        }
    }

    /**
     * 平台 API Key：回填 hash + 把明文换成密文。
     * <p>
     * 判据用"hash 为空"而不是"没有 enc:v1: 前缀"：只有 hash 才是查找与唯一键的依据，
     * hash 补齐了，行就完整了。
     */
    private int migrateTenantApiKeys(TenantApiKeyRepository repository) {
        if (repository == null) {
            return 0;
        }
        List<TenantApiKeyEntity> pending = repository.findAll()
                .filter(each -> !StringUtils.hasText(each.getApiKeyHash()))
                .collectList()
                .block();
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        int migrated = 0;
        for (TenantApiKeyEntity entity : pending) {
            String plain;
            try {
                plain = secretCipher.decrypt(entity.getApiKey());
            } catch (RuntimeException ex) {
                log.error("cannot migrate tenant api key row id={} tenantId={}: {}",
                        entity.getId(), entity.getTenantId(), ex.getMessage());
                continue;
            }
            if (!StringUtils.hasText(plain)) {
                continue;
            }
            entity.setApiKey(secretCipher.encrypt(plain));
            entity.setApiKeyHash(secretHasher.hmac(plain));
            repository.save(entity).block();
            migrated++;
        }
        return migrated;
    }

    /**
     * 上游单 Key 凭证：{@code api_key} 的唯一键不含 Key 本身，所以只加密、不需要哈希。
     */
    private int migrateProviderCredentials(ProviderCredentialRepository repository) {
        if (repository == null) {
            return 0;
        }
        List<ProviderCredentialEntity> pending = repository.findAll()
                .filter(each -> needsEncryption(each.getApiKey()))
                .collectList()
                .block();
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        int migrated = 0;
        for (ProviderCredentialEntity entity : pending) {
            entity.setApiKey(secretCipher.encrypt(entity.getApiKey()));
            repository.save(entity).block();
            migrated++;
        }
        return migrated;
    }

    /**
     * 渠道 Key 池：与单 Key 凭证同理。
     */
    private int migrateProviderKeyPool(ProviderCredentialKeyRepository repository) {
        if (repository == null) {
            return 0;
        }
        List<ProviderCredentialKeyEntity> pending = repository.findAll()
                .filter(each -> needsEncryption(each.getApiKey()))
                .collectList()
                .block();
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        int migrated = 0;
        for (ProviderCredentialKeyEntity entity : pending) {
            entity.setApiKey(secretCipher.encrypt(entity.getApiKey()));
            repository.save(entity).block();
            migrated++;
        }
        return migrated;
    }

    private boolean needsEncryption(String storedApiKey) {
        return StringUtils.hasText(storedApiKey) && !AesGcmSecretCipher.isEncrypted(storedApiKey);
    }

    /**
     * 控制台口令没法"回写"——yml 是只读的，所以这里只能把结果告诉运维。
     * <p>
     * 只在非 prod 打印现成的哈希：prod 里明文口令会直接被
     * {@code ProductionSecurityPropertiesValidator} 拦在启动阶段，走不到这儿。
     */
    private void warnPlaintextConsolePasswords() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        for (Map.Entry<String, AiGatewaySecurityProperties.UserCredential> entry
                : properties.getSecurity().getUsers().entrySet()) {
            String password = entry.getValue() == null ? null : entry.getValue().getPassword();
            if (passwordEncoderSupport.isEncoded(password)) {
                continue;
            }
            if (prod) {
                log.warn("console user '{}' stores a plaintext password, prod requires a bcrypt hash", entry.getKey());
            } else {
                log.warn("console user '{}' stores a plaintext password; to migrate, replace it with: {}",
                        entry.getKey(), passwordEncoderSupport.encode(password));
            }
        }
    }

    private boolean acquireLock() {
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(LOCK_KEY, String.valueOf(System.currentTimeMillis()), LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception ex) {
            // Redis 不可用不能反过来阻断迁移：迁移幂等，并发也只是重复写等价值
            log.warn("redis unavailable for the secret migration lock, proceeding anyway: {}", ex.getMessage());
            return true;
        }
    }

    private void releaseLock() {
        try {
            stringRedisTemplate.delete(LOCK_KEY);
        } catch (Exception ex) {
            // 有 TTL 兜底，删不掉最多让别的实例晚一分钟迁移
            log.debug("failed to release the secret migration lock: {}", ex.getMessage());
        }
    }
}
