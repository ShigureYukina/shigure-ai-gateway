package com.nageoffer.shortlink.aigateway.tenant;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TenantModelPolicyService {

    private final AiGatewayProperties properties;

    private final TenantConfigQueryService tenantConfigQueryService;

    @Autowired
    public TenantModelPolicyService(AiGatewayProperties properties, TenantConfigQueryService tenantConfigQueryService) {
        this.properties = properties;
        this.tenantConfigQueryService = tenantConfigQueryService;
    }

    public TenantModelPolicyService(AiGatewayProperties properties) {
        this(properties, TenantConfigQueryService.fallbackOnly(properties));
    }

    public String resolveModel(TenantContext tenantContext, String requestModel) {
        if (!properties.getTenant().isEnabled()) {
            return requestModel;
        }
        AiGatewayProperties.TenantModelPolicy policy = tenantConfigQueryService.findModelPolicy(tenantContext.tenantId()).orElse(null);
        if (policy == null || !policy.isEnabled()) {
            return requestModel;
        }

        String effectiveModel = resolveEffectiveModel(policy, requestModel);
        if (!StringUtils.hasText(effectiveModel)) {
            throw new AiGatewayClientException(AiGatewayErrorCode.FORBIDDEN, "租户未配置有效默认模型");
        }
        if (!policy.getAllowedModels().isEmpty() && !policy.getAllowedModels().contains(effectiveModel)) {
            throw new AiGatewayClientException(AiGatewayErrorCode.FORBIDDEN, "当前租户无权访问模型: " + requestModel);
        }
        return effectiveModel;
    }

    private String resolveEffectiveModel(AiGatewayProperties.TenantModelPolicy policy, String requestModel) {
        String mappedModel = policy.getModelMappings().get(requestModel);
        if (StringUtils.hasText(mappedModel)) {
            return mappedModel;
        }
        if (StringUtils.hasText(policy.getDefaultModel()) && StringUtils.hasText(requestModel)
                && requestModel.equals(policy.getDefaultModelAlias())) {
            return policy.getDefaultModel();
        }
        return requestModel;
    }
}
