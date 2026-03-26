package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.TenantQuotaPolicyEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface TenantQuotaPolicyRepository extends ReactiveCrudRepository<TenantQuotaPolicyEntity, Long> {

    Mono<TenantQuotaPolicyEntity> findByTenantId(String tenantId);

    Mono<Void> deleteByTenantId(String tenantId);
}
