package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderGroupEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProviderGroupRepository extends ReactiveCrudRepository<ProviderGroupEntity, Long> {

    @Override
    Flux<ProviderGroupEntity> findAll();

    Mono<ProviderGroupEntity> findByGroupName(String groupName);

    Mono<Void> deleteByGroupName(String groupName);
}
