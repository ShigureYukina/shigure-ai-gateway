package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderGroupMemberEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProviderGroupMemberRepository extends ReactiveCrudRepository<ProviderGroupMemberEntity, Long> {

    @Override
    Flux<ProviderGroupMemberEntity> findAll();

    Flux<ProviderGroupMemberEntity> findByGroupName(String groupName);

    Mono<Void> deleteByGroupName(String groupName);
}
