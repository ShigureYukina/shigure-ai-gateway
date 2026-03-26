package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.routing.ProviderRoutingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/routing")
@Tag(name = "路由策略", description = "多上游路由与 fallback 运行时配置")
public class AiRoutingController {

    private final ProviderRoutingService providerRoutingService;

    @Operation(summary = "查询路由配置", description = "返回 defaultProvider/providerBaseUrl/modelAlias/fallback 配置")
    @GetMapping("/config")
    public Map<String, Object> config() {
        return providerRoutingService.routingConfig();
    }

    @Operation(summary = "更新路由配置", description = "运行时更新路由配置")
    @PostMapping("/config")
    public Map<String, Object> update(@RequestBody Map<String, Object> requestParam) {
        return providerRoutingService.updateRoutingConfig(requestParam);
    }

    @Operation(summary = "路由预览", description = "按模型和请求头预览主路由及 fallback 候选")
    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam("model") String model, ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        return providerRoutingService.preview(model, headers);
    }

    @Operation(summary = "A/B 分桶仿真", description = "按模型模拟 userId 分桶，返回命中率与 provider 分布")
    @GetMapping("/simulate")
    public Map<String, Object> simulate(@RequestParam("model") String model,
                                        @RequestParam(value = "samples", defaultValue = "200") int samples) {
        return providerRoutingService.simulateAb(model, samples);
    }
}
