package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.TenantApiKeyEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TenantApiKeyRepository extends ReactiveCrudRepository<TenantApiKeyEntity, Long> {

    Mono<TenantApiKeyEntity> findByApiKey(String apiKey);

    Flux<TenantApiKeyEntity> findAllByTenantId(String tenantId);

    Mono<Void> deleteByApiKey(String apiKey);

    Mono<Void> deleteAllByTenantId(String tenantId);
}
