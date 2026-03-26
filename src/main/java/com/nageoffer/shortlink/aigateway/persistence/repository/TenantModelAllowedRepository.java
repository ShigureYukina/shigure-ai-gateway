package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelAllowedEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TenantModelAllowedRepository extends ReactiveCrudRepository<TenantModelAllowedEntity, Long> {

    Flux<TenantModelAllowedEntity> findAllByTenantId(String tenantId);

    Mono<Void> deleteAllByTenantId(String tenantId);
}
