package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.RuntimeConfigEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/**
 * 运行时配置仓库。
 * <p>
 * 只在 {@code short-link.ai-gateway.tenant.persistence.enabled=true} 时才注册成 Bean
 * （见 {@code config.TenantR2dbcConfiguration}），消费方一律通过 {@code ObjectProvider} 取，
 * 拿不到就退化为"只改本实例内存"。
 */
public interface RuntimeConfigRepository extends ReactiveCrudRepository<RuntimeConfigEntity, String> {

    Mono<RuntimeConfigEntity> findByDomain(String domain);
}
