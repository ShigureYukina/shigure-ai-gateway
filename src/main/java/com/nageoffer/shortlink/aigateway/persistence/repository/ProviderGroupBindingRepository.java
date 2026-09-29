package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderGroupBindingEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProviderGroupBindingRepository extends ReactiveCrudRepository<ProviderGroupBindingEntity, Long> {

    @Override
    Flux<ProviderGroupBindingEntity> findAll();

    Mono<ProviderGroupBindingEntity> findByModel(String model);

    Mono<Void> deleteByGroupName(String groupName);

    Mono<Void> deleteByModel(String model);
}
