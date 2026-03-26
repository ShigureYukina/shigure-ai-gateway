package com.nageoffer.shortlink.aigateway.observability;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CostEstimatorTest {

    @Test
    void shouldEstimateCostByModelPrice() {
        AiGatewayProperties properties = new AiGatewayProperties();
        AiGatewayProperties.ModelPrice modelPrice = new AiGatewayProperties.ModelPrice();
        modelPrice.setInputPer1k(0.1D);
        modelPrice.setOutputPer1k(0.2D);
        properties.getObservability().getModelPrice().put("gpt-4o-mini", modelPrice);

        CostEstimator estimator = new CostEstimator(properties);
        double cost = estimator.estimate("gpt-4o-mini", 1000, 500);

        Assertions.assertEquals(0.2D, cost, 0.0001);
    }

    @Test
    void shouldReturnZeroWhenModelPriceMissing() {
        AiGatewayProperties properties = new AiGatewayProperties();
        CostEstimator estimator = new CostEstimator(properties);

        Assertions.assertEquals(0D, estimator.estimate("unknown-model", 1000, 500));
    }

    @Test
    void shouldTreatNullPriceFieldsAsZero() {
        AiGatewayProperties properties = new AiGatewayProperties();
        AiGatewayProperties.ModelPrice modelPrice = new AiGatewayProperties.ModelPrice();
        modelPrice.setInputPer1k(null);
        modelPrice.setOutputPer1k(0.5D);
        properties.getObservability().getModelPrice().put("gpt-4o-mini", modelPrice);

        CostEstimator estimator = new CostEstimator(properties);
        double cost = estimator.estimate("gpt-4o-mini", 1000, 1000);

        Assertions.assertEquals(0.5D, cost, 0.0001);
    }

    @Test
    void shouldPreferDatabaseBackedModelPriceWhenAvailable() {
        AiGatewayProperties properties = new AiGatewayProperties();
        AiGatewayProperties.ModelPrice configPrice = new AiGatewayProperties.ModelPrice();
        configPrice.setInputPer1k(0.1D);
        configPrice.setOutputPer1k(0.2D);
        properties.getObservability().getModelPrice().put("gpt-4o-mini", configPrice);

        AiGatewayProperties.ModelPrice dbPrice = new AiGatewayProperties.ModelPrice();
        dbPrice.setInputPer1k(1D);
        dbPrice.setOutputPer1k(2D);
        TenantConfigQueryService queryService = Mockito.mock(TenantConfigQueryService.class);
        Mockito.when(queryService.findModelPrice("gpt-4o-mini")).thenReturn(java.util.Optional.of(dbPrice));

        CostEstimator estimator = new CostEstimator(properties, queryService);
        double cost = estimator.estimate("gpt-4o-mini", 1000, 500);

        Assertions.assertEquals(2D, cost, 0.0001);
    }
}
