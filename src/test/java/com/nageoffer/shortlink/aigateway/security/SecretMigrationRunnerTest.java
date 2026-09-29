package com.nageoffer.shortlink.aigateway.security;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.crypto.AesGcmSecretCipher;
import com.nageoffer.shortlink.aigateway.crypto.MasterKey;
import com.nageoffer.shortlink.aigateway.crypto.SecretCipher;
import com.nageoffer.shortlink.aigateway.crypto.SecretHasher;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderCredentialEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderCredentialKeyEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantApiKeyEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderCredentialKeyRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderCredentialRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantApiKeyRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 存量密钥回填：把开启加密之前留下的明文行就地改写。
 * <p>
 * 关心的是四个"别出事"：没主密钥 / 没开持久化时不动数据库，
 * 迁过的行第二次启动不再写（幂等），一条坏数据不能带倒整批，
 * 以及 Redis 锁只在多实例场景里起到"少干活"的作用，绝不能反过来阻断迁移。
 */
class SecretMigrationRunnerTest {

    private static final MasterKey MASTER_KEY = masterKey();

    private static final SecretCipher CIPHER = AesGcmSecretCipher.of(MASTER_KEY);

    private static final SecretHasher HASHER = SecretHasher.of(MASTER_KEY);

    private final TenantApiKeyRepository tenantApiKeyRepository = Mockito.mock(TenantApiKeyRepository.class);

    private final ProviderCredentialRepository credentialRepository = Mockito.mock(ProviderCredentialRepository.class);

    private final ProviderCredentialKeyRepository keyPoolRepository = Mockito.mock(ProviderCredentialKeyRepository.class);

    private final StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);

    private final ValueOperations<String, String> valueOperations = valueOperations();

    private final PasswordEncoderSupport passwordEncoder = Mockito.spy(new PasswordEncoderSupport());

    @BeforeEach
    void stubEmptyRepositoriesAndFreeLock() {
        // 默认全部空表 + 抢到锁：每个用例只覆写自己关心的那部分
        Mockito.lenient().when(tenantApiKeyRepository.findAll()).thenReturn(Flux.empty());
        Mockito.lenient().when(credentialRepository.findAll()).thenReturn(Flux.empty());
        Mockito.lenient().when(keyPoolRepository.findAll()).thenReturn(Flux.empty());
        Mockito.lenient().when(redis.opsForValue()).thenReturn(valueOperations);
        Mockito.lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
    }

    @Test
    void shouldBackfillHashAndCiphertextForTenantApiKeys() {
        TenantApiKeyEntity legacy = tenantApiKey(1L, "sk-tenant-a");
        Mockito.when(tenantApiKeyRepository.findAll()).thenReturn(Flux.just(legacy));
        Mockito.when(tenantApiKeyRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        runner().migrate();

        ArgumentCaptor<TenantApiKeyEntity> saved = ArgumentCaptor.forClass(TenantApiKeyEntity.class);
        Mockito.verify(tenantApiKeyRepository).save(saved.capture());
        TenantApiKeyEntity migrated = saved.getValue();
        Assertions.assertTrue(AesGcmSecretCipher.isEncrypted(migrated.getApiKey()),
                "落库的必须是 enc:v1: 密文，否则下次启动会被再迁一遍");
        Assertions.assertEquals("sk-tenant-a", CIPHER.decrypt(migrated.getApiKey()),
                "密文要解得回原文，否则查出来的凭证直接打上游 401");
        Assertions.assertEquals(HASHER.hmac("sk-tenant-a"), migrated.getApiKeyHash(),
                "hash 是查找与唯一键的依据，必须与运行期算出来的完全一致");
    }

    @Test
    void shouldNotTouchRowsThatAreAlreadyMigrated() {
        TenantApiKeyEntity done = tenantApiKey(1L, CIPHER.encrypt("sk-tenant-a"));
        done.setApiKeyHash(HASHER.hmac("sk-tenant-a"));
        Mockito.when(tenantApiKeyRepository.findAll()).thenReturn(Flux.just(done));
        Mockito.when(credentialRepository.findAll()).thenReturn(Flux.just(credential(1L, CIPHER.encrypt("sk-upstream"))));
        Mockito.when(keyPoolRepository.findAll()).thenReturn(Flux.just(pooledKey(1L, CIPHER.encrypt("sk-pooled"))));

        runner().migrate();

        // 幂等：没有"已迁移"标记表，判据就是行本身的状态，所以第二次启动必须零写入
        Mockito.verify(tenantApiKeyRepository, Mockito.never()).save(any());
        Mockito.verify(credentialRepository, Mockito.never()).save(any());
        Mockito.verify(keyPoolRepository, Mockito.never()).save(any());
    }

    @Test
    void shouldEncryptPlaintextUpstreamCredentialAndPooledKey() {
        Mockito.when(credentialRepository.findAll()).thenReturn(Flux.just(credential(1L, "sk-upstream")));
        Mockito.when(keyPoolRepository.findAll()).thenReturn(Flux.just(pooledKey(2L, "sk-pooled")));
        Mockito.when(credentialRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(keyPoolRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        runner().migrate();

        ArgumentCaptor<ProviderCredentialEntity> credentialSaved = ArgumentCaptor.forClass(ProviderCredentialEntity.class);
        Mockito.verify(credentialRepository).save(credentialSaved.capture());
        Assertions.assertEquals("sk-upstream", CIPHER.decrypt(credentialSaved.getValue().getApiKey()));

        ArgumentCaptor<ProviderCredentialKeyEntity> pooledSaved = ArgumentCaptor.forClass(ProviderCredentialKeyEntity.class);
        Mockito.verify(keyPoolRepository).save(pooledSaved.capture());
        Assertions.assertEquals("sk-pooled", CIPHER.decrypt(pooledSaved.getValue().getApiKey()));
    }

    @Test
    void shouldSkipUndecryptableRowWithoutLosingTheOthers() {
        TenantApiKeyEntity corrupt = tenantApiKey(1L, AesGcmSecretCipher.PREFIX + "!!!not-base64!!!");
        TenantApiKeyEntity healthy = tenantApiKey(2L, "sk-tenant-b");
        Mockito.when(tenantApiKeyRepository.findAll()).thenReturn(Flux.just(corrupt, healthy));
        Mockito.when(tenantApiKeyRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        Assertions.assertDoesNotThrow(() -> runner().migrate());

        ArgumentCaptor<TenantApiKeyEntity> saved = ArgumentCaptor.forClass(TenantApiKeyEntity.class);
        Mockito.verify(tenantApiKeyRepository).save(saved.capture());
        Assertions.assertEquals(2L, saved.getValue().getId(),
                "坏行跳过自己即可，不能让一条解不开的数据挡住其余回填");
    }

    @Test
    void shouldDoNothingWhenPersistenceIsDisabled() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getTenant().getPersistence().setEnabled(false);

        runner(properties).migrate();

        Mockito.verify(tenantApiKeyRepository, Mockito.never()).findAll();
        Mockito.verify(redis, Mockito.never()).opsForValue();
    }

    @Test
    void shouldDoNothingWhenMasterKeyIsAbsent() {
        // 没配 AI_GATEWAY_MASTER_KEY：既没有"待加密"这回事，也生不出查找哈希，只能原样跳过
        new SecretMigrationRunner(persistenceEnabled(), AesGcmSecretCipher.disabled(), SecretHasher.disabled(),
                passwordEncoder, redis, environment(), provider(tenantApiKeyRepository),
                provider(credentialRepository), provider(keyPoolRepository)).migrate();

        Mockito.verify(tenantApiKeyRepository, Mockito.never()).findAll();
        Mockito.verify(redis, Mockito.never()).opsForValue();
    }

    @Test
    void shouldDoNothingWhenNoRepositoryIsRegistered() {
        // 运行时找不到仓储（未开 R2DBC 扫描）时不能 NPE
        Assertions.assertDoesNotThrow(() -> new SecretMigrationRunner(persistenceEnabled(), CIPHER, HASHER,
                passwordEncoder, redis, environment(), provider(null), provider(null), provider(null)).migrate());
    }

    @Test
    void shouldSkipMigrationWhenAnotherInstanceHoldsTheLock() {
        Mockito.when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        Mockito.when(tenantApiKeyRepository.findAll()).thenReturn(Flux.just(tenantApiKey(1L, "sk-tenant-a")));

        runner().migrate();

        Mockito.verify(tenantApiKeyRepository, Mockito.never()).findAll();
        // 没抢到锁就不该去删别人的锁
        Mockito.verify(redis, Mockito.never()).delete(anyString());
    }

    @Test
    void shouldProceedWhenRedisIsUnavailable() {
        Mockito.when(redis.opsForValue()).thenThrow(new IllegalStateException("connection refused"));
        Mockito.when(tenantApiKeyRepository.findAll()).thenReturn(Flux.just(tenantApiKey(1L, "sk-tenant-a")));
        Mockito.when(tenantApiKeyRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        runner().migrate();

        // 锁是"少干活"的优化，不是正确性前提：Redis 挂了照样迁，重复迁也只会写入等价值
        Mockito.verify(tenantApiKeyRepository).save(any());
    }

    @Test
    void shouldPrintReadyToPasteHashForPlaintextConsolePassword() {
        runner().migrate();

        // yml 只读，回写做不到；能做的就是把现成的哈希打给运维
        Mockito.verify(passwordEncoder).encode("admin123456");
        Mockito.verify(passwordEncoder).encode("viewer123456");
    }

    @Test
    void shouldNotReHashAnAlreadyEncodedConsolePassword() {
        AiGatewayProperties properties = persistenceEnabled();
        PasswordEncoderSupport encoder = new PasswordEncoderSupport();
        properties.getSecurity().getUsers().forEach((user, credential) ->
                credential.setPassword(encoder.encode(credential.getPassword())));

        runner(properties).migrate();

        // 已经是 {bcrypt} 的别再打一遍，否则每次启动都刷一行无意义的日志
        Mockito.verify(passwordEncoder, Mockito.never()).encode(anyString());
    }

    @Test
    void shouldOnlyWarnWithoutPrintingHashesInProd() {
        // prod 里明文口令会被 ProductionSecurityPropertiesValidator 拦在启动阶段，
        // 这里只保证不会把"可直接粘贴的哈希"当运维提示打出来
        runner(persistenceEnabled(), "prod").migrate();

        Mockito.verify(passwordEncoder, Mockito.never()).encode(anyString());
    }

    private SecretMigrationRunner runner() {
        return runner(persistenceEnabled());
    }

    private SecretMigrationRunner runner(AiGatewayProperties properties, String... activeProfiles) {
        return new SecretMigrationRunner(properties, CIPHER, HASHER, passwordEncoder, redis,
                environment(activeProfiles), provider(tenantApiKeyRepository), provider(credentialRepository),
                provider(keyPoolRepository));
    }

    private static Environment environment(String... activeProfiles) {
        Environment environment = Mockito.mock(Environment.class);
        Mockito.lenient().when(environment.getActiveProfiles()).thenReturn(activeProfiles);
        return environment;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> valueOperations() {
        return Mockito.mock(ValueOperations.class);
    }

    private static AiGatewayProperties persistenceEnabled() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getTenant().getPersistence().setEnabled(true);
        return properties;
    }

    private static TenantApiKeyEntity tenantApiKey(Long id, String storedApiKey) {
        TenantApiKeyEntity entity = new TenantApiKeyEntity();
        entity.setId(id);
        entity.setTenantId("tenant-a");
        entity.setAppId("app-a");
        entity.setKeyId("key-" + id);
        entity.setApiKey(storedApiKey);
        entity.setEnabled(true);
        return entity;
    }

    private static ProviderCredentialEntity credential(Long id, String storedApiKey) {
        ProviderCredentialEntity entity = new ProviderCredentialEntity();
        entity.setId(id);
        entity.setTenantId(ProviderCredentialEntity.GLOBAL_TENANT_ID);
        entity.setProvider("openai");
        entity.setApiKey(storedApiKey);
        entity.setEnabled(true);
        return entity;
    }

    private static ProviderCredentialKeyEntity pooledKey(Long id, String storedApiKey) {
        ProviderCredentialKeyEntity entity = new ProviderCredentialKeyEntity();
        entity.setId(id);
        entity.setTenantId(ProviderCredentialEntity.GLOBAL_TENANT_ID);
        entity.setProvider("openai");
        entity.setKeyId("key-" + id);
        entity.setApiKey(storedApiKey);
        entity.setWeight(1);
        entity.setEnabled(true);
        return entity;
    }

    private static MasterKey masterKey() {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 9);
        return MasterKey.fromBase64(Base64.getEncoder().encodeToString(bytes));
    }
}
