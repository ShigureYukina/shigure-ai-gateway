package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderCredentialKeyEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProviderCredentialKeyRepository extends ReactiveCrudRepository<ProviderCredentialKeyEntity, Long> {

    @Override
    Flux<ProviderCredentialKeyEntity> findAll();

    Flux<ProviderCredentialKeyEntity> findByTenantIdAndProvider(String tenantId, String provider);

    Mono<Void> deleteByTenantIdAndProvider(String tenantId, String provider);

    Mono<Void> deleteAllByTenantId(String tenantId);
}
