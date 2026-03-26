package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelPolicyEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface TenantModelPolicyRepository extends ReactiveCrudRepository<TenantModelPolicyEntity, Long> {

    Mono<TenantModelPolicyEntity> findByTenantId(String tenantId);

    Mono<Void> deleteByTenantId(String tenantId);
}
