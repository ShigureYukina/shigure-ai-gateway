package com.nageoffer.shortlink.aigateway.persistence.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTenantProperties;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantQuotaPolicyEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantQuotaPolicyRepository;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户配额域：分钟/日/月 token 额度。
 * <p>
 * 边界：只负责 {@code tenant_quota_policy}。这里只回答"额度是多少"，
 * 实际扣减与回滚在 {@code RedisTokenQuotaService}——把"配置"与"计数"分开，才不会出现两处都在改额度。
 */
public class TenantQuotaPolicyLookup {

    private final AiGatewayProperties properties;

    private final TenantQuotaPolicyRepository repository;

    private final Map<String, AiGatewayTenantProperties.TenantQuotaPolicy> cache = new ConcurrentHashMap<>();

    public TenantQuotaPolicyLookup(AiGatewayProperties properties, TenantQuotaPolicyRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    public boolean ready() {
        return repository != null;
    }

    public Mono<List<TenantQuotaPolicyEntity>> load() {
        if (!ready()) {
            return Mono.empty();
        }
        return repository.findAll().collectList();
    }

    public void apply(List<TenantQuotaPolicyEntity> entities) {
        cache.clear();
        entities.forEach(each -> cache.put(each.getTenantId(), toQuotaPolicy(each)));
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    public Optional<AiGatewayTenantProperties.TenantQuotaPolicy> find(String tenantId) {
        return DomainLookupSupport.preferSnapshot(properties, cache.get(tenantId),
                () -> properties.getTenant().getQuotaPolicies().get(tenantId));
    }

    private AiGatewayTenantProperties.TenantQuotaPolicy toQuotaPolicy(TenantQuotaPolicyEntity entity) {
        AiGatewayTenantProperties.TenantQuotaPolicy policy = new AiGatewayTenantProperties.TenantQuotaPolicy();
        policy.setEnabled(Boolean.TRUE.equals(entity.getEnabled()));
        policy.setTokenQuotaPerMinute(entity.getTokenQuotaPerMinute());
        policy.setTokenQuotaPerDay(entity.getTokenQuotaPerDay());
        policy.setTokenQuotaPerMonth(entity.getTokenQuotaPerMonth());
        return policy;
    }
}
