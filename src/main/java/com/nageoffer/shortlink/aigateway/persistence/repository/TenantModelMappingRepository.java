package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelMappingEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TenantModelMappingRepository extends ReactiveCrudRepository<TenantModelMappingEntity, Long> {

    Flux<TenantModelMappingEntity> findAllByTenantId(String tenantId);

    Mono<Void> deleteAllByTenantId(String tenantId);
}
