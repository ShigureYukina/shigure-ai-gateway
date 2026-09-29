package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.AiModelPriceSyncEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AiModelPriceSyncRepository extends ReactiveCrudRepository<AiModelPriceSyncEntity, Long> {

    @Override
    Flux<AiModelPriceSyncEntity> findAll();

    Flux<AiModelPriceSyncEntity> findBySource(String source);

    Mono<Void> deleteBySource(String source);
}
