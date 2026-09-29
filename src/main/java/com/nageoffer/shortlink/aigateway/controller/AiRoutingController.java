package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.routing.ProviderRoutingService;
import com.nageoffer.shortlink.aigateway.routing.RoutingPlanResolver;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigDomain;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigPublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/routing")
@Tag(name = "路由策略", description = "多上游路由与 fallback 运行时配置")
public class AiRoutingController {

    private final ProviderRoutingService providerRoutingService;

    private final RoutingPlanResolver routingPlanResolver;

    private final RuntimeConfigPublisher runtimeConfigPublisher;

    @Autowired
    public AiRoutingController(ProviderRoutingService providerRoutingService,
                               RoutingPlanResolver routingPlanResolver,
                               RuntimeConfigPublisher runtimeConfigPublisher) {
        this.providerRoutingService = providerRoutingService;
        this.routingPlanResolver = routingPlanResolver;
        this.runtimeConfigPublisher = runtimeConfigPublisher;
    }

    /**
     * 保留给不接配置中心的单元测试：写入只改本实例内存。
     */
    public AiRoutingController(ProviderRoutingService providerRoutingService, RoutingPlanResolver routingPlanResolver) {
        this(providerRoutingService, routingPlanResolver, RuntimeConfigPublisher.noPersistence());
    }

    @Operation(summary = "查询路由配置", description = "返回 defaultProvider/providerBaseUrl/modelAlias/fallback 配置")
    @GetMapping("/config")
    public Map<String, Object> config() {
        return providerRoutingService.routingConfig();
    }

    @Operation(summary = "更新路由配置",
            description = "运行时更新路由配置；响应里的 persisted=false 表示当前实例未接持久化，仅本实例生效")
    @PostMapping("/config")
    public Mono<Map<String, Object>> update(@RequestBody Map<String, Object> requestParam) {
        // 注意 defaultProvider 不在 ROUTING 的落库快照里（见 RuntimeConfigSupport），改了它只在本实例生效；
        // 它属于部署期拓扑，与 providerCredentials 同列。
        // 调度到 boundedElastic：改 providerBaseUrl 要解析 DNS 做 SSRF 校验，不能在 event loop 上阻塞。
        return Mono.fromCallable(() -> providerRoutingService.updateRoutingConfig(requestParam))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(view -> runtimeConfigPublisher.save(RuntimeConfigDomain.ROUTING, view));
    }

    @Operation(summary = "路由预览",
            description = "按模型和请求头预览主路由及 fallback 候选；带 tenantId 时按该租户的模型策略解析，与真实链路一致")
    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam("model") String model,
                                       @RequestParam(value = "tenantId", required = false) String tenantId,
                                       ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        return routingPlanResolver.preview(model, headers, tenantId);
    }

    @Operation(summary = "A/B 分桶仿真", description = "按模型模拟 userId 分桶，返回命中率与 provider 分布")
    @GetMapping("/simulate")
    public Map<String, Object> simulate(@RequestParam("model") String model,
                                        @RequestParam(value = "samples", defaultValue = "200") int samples) {
        return providerRoutingService.simulateAb(model, samples);
    }

    @Operation(summary = "查询 Provider 健康分数", description = "返回指定模型下各 Provider 的动态路由健康评分")
    @GetMapping("/health")
    public List<?> health(@RequestParam("model") String model) {
        return providerRoutingService.providerHealthScores(model);
    }
}
