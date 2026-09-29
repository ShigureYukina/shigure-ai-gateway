package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderCredentialEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProviderCredentialRepository extends ReactiveCrudRepository<ProviderCredentialEntity, Long> {

    @Override
    Flux<ProviderCredentialEntity> findAll();

    Mono<ProviderCredentialEntity> findByTenantIdAndProvider(String tenantId, String provider);

    Mono<Void> deleteByTenantIdAndProvider(String tenantId, String provider);

    Mono<Void> deleteAllByTenantId(String tenantId);
}
