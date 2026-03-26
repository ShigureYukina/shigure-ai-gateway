package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.TenantAppEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TenantAppRepository extends ReactiveCrudRepository<TenantAppEntity, Long> {

    Flux<TenantAppEntity> findAllByTenantId(String tenantId);

    Mono<TenantAppEntity> findByTenantIdAndAppId(String tenantId, String appId);

    Mono<Void> deleteByTenantIdAndAppId(String tenantId, String appId);

    Mono<Void> deleteAllByTenantId(String tenantId);
}
