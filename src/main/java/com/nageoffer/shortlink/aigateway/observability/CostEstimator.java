package com.nageoffer.shortlink.aigateway.observability;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CostEstimator {

    private final AiGatewayProperties properties;

    private final TenantConfigQueryService tenantConfigQueryService;

    @Autowired
    public CostEstimator(AiGatewayProperties properties, TenantConfigQueryService tenantConfigQueryService) {
        this.properties = properties;
        this.tenantConfigQueryService = tenantConfigQueryService;
    }

    public CostEstimator(AiGatewayProperties properties) {
        this(properties, TenantConfigQueryService.fallbackOnly(properties));
    }

    public double estimate(String model, long tokenIn, long tokenOut) {
        AiGatewayProperties.ModelPrice price = tenantConfigQueryService.findModelPrice(model)
                .orElse(properties.getObservability().getModelPrice().get(model));
        if (price == null) {
            return 0D;
        }
        double inputCost = tokenIn / 1000D * nullToZero(price.getInputPer1k());
        double outputCost = tokenOut / 1000D * nullToZero(price.getOutputPer1k());
        return inputCost + outputCost;
    }

    private double nullToZero(Double value) {
        return value == null ? 0D : value;
    }
}
