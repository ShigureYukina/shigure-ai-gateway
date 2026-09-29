package com.nageoffer.shortlink.aigateway.persistence.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTenantProperties;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelAllowedEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelMappingEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.TenantModelPolicyEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelAllowedRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelMappingRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.TenantModelPolicyRepository;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 租户模型策略域：一个租户能用哪些模型、请求模型怎么映射到上游模型。
 * <p>
 * 边界：{@code tenant_model_policy} + {@code tenant_model_policy_allowed_model} + {@code tenant_model_mapping}
 * 三张表同属一个域，因为它们合起来才构成一条完整策略——allowed 决定"能不能用"、mapping 决定"映射到谁"、
 * policy 决定"默认送哪个"。任何一张表缺失，策略都不完整，所以它们同生共死（ready() 要求三者都在）。
 * <p>
 * 关键语义：只有 allowed 行、没有 policy 行的租户 <b>不构成策略</b>；否则会凭空放开一个从未配置过的租户。
 */
public class TenantModelPolicyLookup {

    private final AiGatewayProperties properties;

    private final TenantModelPolicyRepository policyRepository;

    private final TenantModelAllowedRepository allowedRepository;

    private final TenantModelMappingRepository mappingRepository;

    private final Map<String, AiGatewayTenantProperties.TenantModelPolicy> cache = new ConcurrentHashMap<>();

    public TenantModelPolicyLookup(AiGatewayProperties properties,
                                   TenantModelPolicyRepository policyRepository,
                                   TenantModelAllowedRepository allowedRepository,
                                   TenantModelMappingRepository mappingRepository) {
        this.properties = properties;
        this.policyRepository = policyRepository;
        this.allowedRepository = allowedRepository;
        this.mappingRepository = mappingRepository;
    }

    public boolean ready() {
        return policyRepository != null && allowedRepository != null && mappingRepository != null;
    }

    public Mono<Snapshot> load() {
        if (!ready()) {
            return Mono.empty();
        }
        return Mono.zip(
                        policyRepository.findAll().collectList(),
                        allowedRepository.findAll().collectList(),
                        mappingRepository.findAll().collectList())
                .map(tuple -> new Snapshot(tuple.getT1(), tuple.getT2(), tuple.getT3()));
    }

    /**
     * 整体替换快照：allowed 与 mapping 按 tenantId 归并后挂到各自的策略上。
     */
    public void apply(Snapshot snapshot) {
        cache.clear();
        Map<String, Set<String>> allowedByTenant = snapshot.allowedModels().stream()
                .collect(Collectors.groupingBy(TenantModelAllowedEntity::getTenantId,
                        Collectors.mapping(TenantModelAllowedEntity::getAllowedModel, Collectors.toSet())));
        Map<String, Map<String, String>> mappingsByTenant = snapshot.modelMappings().stream()
                .collect(Collectors.groupingBy(TenantModelMappingEntity::getTenantId,
                        Collectors.toMap(TenantModelMappingEntity::getRequestModel,
                                TenantModelMappingEntity::getProviderModel, (left, right) -> right, HashMap::new)));
        snapshot.policies().forEach(each -> cache.put(each.getTenantId(), toPolicy(each,
                allowedByTenant.getOrDefault(each.getTenantId(), Collections.emptySet()),
                mappingsByTenant.getOrDefault(each.getTenantId(), Collections.emptyMap()))));
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    public Optional<AiGatewayTenantProperties.TenantModelPolicy> find(String tenantId) {
        return DomainLookupSupport.preferSnapshot(properties, cache.get(tenantId),
                () -> properties.getTenant().getModelPolicies().get(tenantId));
    }

    private AiGatewayTenantProperties.TenantModelPolicy toPolicy(TenantModelPolicyEntity entity, Set<String> allowedModels,
                                                          Map<String, String> mappings) {
        AiGatewayTenantProperties.TenantModelPolicy policy = new AiGatewayTenantProperties.TenantModelPolicy();
        policy.setEnabled(Boolean.TRUE.equals(entity.getEnabled()));
        policy.setAllowedModels(allowedModels);
        policy.setModelMappings(mappings);
        policy.setDefaultModelAlias(entity.getDefaultModelAlias());
        policy.setDefaultModel(entity.getDefaultModel());
        return policy;
    }

    /**
     * 一次全量读取的结果：三张表都必须到齐才能构成策略。
     */
    public record Snapshot(List<TenantModelPolicyEntity> policies,
                           List<TenantModelAllowedEntity> allowedModels,
                           List<TenantModelMappingEntity> modelMappings) {
    }
}
