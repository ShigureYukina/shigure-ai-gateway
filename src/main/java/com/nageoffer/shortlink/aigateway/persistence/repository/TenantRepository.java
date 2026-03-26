package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.TenantEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface TenantRepository extends ReactiveCrudRepository<TenantEntity, Long> {

    Mono<TenantEntity> findByTenantId(String tenantId);

    Mono<Void> deleteByTenantId(String tenantId);
}
