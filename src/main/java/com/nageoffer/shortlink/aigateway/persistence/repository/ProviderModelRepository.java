package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderModelEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ProviderModelRepository extends ReactiveCrudRepository<ProviderModelEntity, Long> {

    @Override
    Flux<ProviderModelEntity> findAll();
}
