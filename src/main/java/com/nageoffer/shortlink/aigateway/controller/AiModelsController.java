package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayTenantProperties;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import com.nageoffer.shortlink.aigateway.routing.AiRoutingResult;
import com.nageoffer.shortlink.aigateway.routing.ModelAliasResolver;
import com.nageoffer.shortlink.aigateway.routing.ProviderRoutingService;
import com.nageoffer.shortlink.aigateway.security.ApiKeyAuthService;
import com.nageoffer.shortlink.aigateway.sync.ModelCatalogSyncService;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据面模型清单。
 * <p>
 * OpenAI SDK 与各类客户端会先探测 {@code /v1/models}，缺少它会让"兼容"停在纸面上。
 * 返回的是调用方当前凭证真正可用的模型（而非平台全部模型），
 * 否则客户端会照着清单请求然后收到 403。
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "AI 网关入口", description = "OpenAI 兼容模型清单")
public class AiModelsController {

    private final ApiKeyAuthService apiKeyAuthService;

    private final AiGatewayProperties properties;

    private final TenantConfigQueryService tenantConfigQueryService;

    private final ModelCatalogSyncService modelCatalogSyncService;

    private final ProviderRoutingService providerRoutingService;

    @Operation(summary = "模型清单", description = "OpenAI 兼容 /v1/models，按当前凭证返回可用模型")
    @GetMapping("/models")
    public Map<String, Object> listModels(ServerWebExchange exchange) {
        TenantContext tenantContext = apiKeyAuthService.authenticate(exchange.getRequest().getHeaders());
        long created = Instant.now().getEpochSecond();
        List<Map<String, Object>> data = new ArrayList<>();
        for (String model : resolveAvailableModels(tenantContext, exchange)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", model);
            item.put("object", "model");
            item.put("created", created);
            item.put("owned_by", "ai-gateway");
            data.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", data);
        return response;
    }

    private List<String> resolveAvailableModels(TenantContext tenantContext, ServerWebExchange exchange) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        AiGatewayTenantProperties.TenantModelPolicy policy = resolveTenantPolicy(tenantContext);
        if (policy != null && policy.isEnabled() && !policy.getAllowedModels().isEmpty()) {
            models.addAll(policy.getAllowedModels());
            if (StringUtils.hasText(policy.getDefaultModel())) {
                models.add(policy.getDefaultModel());
            }
            // 别名是客户端最常用的调用方式，指向可用时才列出，避免列出必然 403 的名字
            policy.getModelMappings().forEach((alias, target) -> {
                if (models.contains(target)) {
                    models.add(alias);
                }
            });
            if (StringUtils.hasText(policy.getDefaultModelAlias())) {
                models.add(policy.getDefaultModelAlias());
            }
            return List.copyOf(models);
        }
        properties.getUpstream().getModelAlias().forEach((alias, target) -> {
            models.add(alias);
            String targetModel = ModelAliasResolver.parse(target).model();
            if (targetModel != null) {
                models.add(targetModel);
            }
        });
        addDiscoveredModels(exchange, models);
        return List.copyOf(models);
    }

    /**
     * 把自动发现的模型并入清单。
     * <p>
     * 只列"按当前路由真的会落到那条通道、且该通道确实报过这个名字"的模型：
     * 发现列表是渠道维度的，直接全列会给客户端一个必然 4xx 的清单。
     */
    private void addDiscoveredModels(ServerWebExchange exchange, LinkedHashSet<String> models) {
        Set<String> discovered = modelCatalogSyncService.allModels();
        if (discovered.isEmpty()) {
            return;
        }
        for (String model : discovered) {
            String provider;
            String providerModel;
            try {
                AiRoutingResult routing = providerRoutingService.resolve(model, exchange.getRequest().getHeaders());
                provider = routing.getProvider();
                providerModel = routing.getProviderModel();
            } catch (RuntimeException ex) {
                continue;
            }
            if (modelCatalogSyncService.modelsOf(provider).contains(providerModel)) {
                models.add(model);
            }
        }
    }

    private AiGatewayTenantProperties.TenantModelPolicy resolveTenantPolicy(TenantContext tenantContext) {
        if (!properties.getTenant().isEnabled()) {
            return null;
        }
        return tenantConfigQueryService.findModelPolicy(tenantContext.tenantId()).orElse(null);
    }
}
