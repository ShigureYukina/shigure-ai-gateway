package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.AiModelPriceEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface AiModelPriceRepository extends ReactiveCrudRepository<AiModelPriceEntity, Long> {

    Mono<AiModelPriceEntity> findByModel(String model);

    Mono<Void> deleteByModel(String model);
}
