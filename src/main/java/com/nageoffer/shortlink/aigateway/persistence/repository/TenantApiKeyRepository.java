package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.TenantApiKeyEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TenantApiKeyRepository extends ReactiveCrudRepository<TenantApiKeyEntity, Long> {

    Mono<TenantApiKeyEntity> findByApiKey(String apiKey);

    /**
     * 配了主密钥时的查找入口（见 {@code crypto.SecretHasher}）：密文不固定，只能按确定性哈希查。
     */
    Mono<TenantApiKeyEntity> findByApiKeyHash(String apiKeyHash);

    Flux<TenantApiKeyEntity> findAllByTenantId(String tenantId);

    Mono<Void> deleteByApiKey(String apiKey);

    Mono<Void> deleteByApiKeyHash(String apiKeyHash);

    Mono<Void> deleteAllByTenantId(String tenantId);
}
