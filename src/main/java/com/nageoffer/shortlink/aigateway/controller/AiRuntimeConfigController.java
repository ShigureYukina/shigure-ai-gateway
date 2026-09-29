package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigDomain;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigPublisher;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigService;
import com.nageoffer.shortlink.aigateway.runtime.RuntimeConfigSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运行时配置中心的管理面。
 * <p>
 * 只做三件事：看当前生效值（{@code GET}）、强制重载（{@code POST /reload}）、恢复出厂（{@code DELETE}）。
 * 改值不在这里 —— 每个域有自己的业务入口（{@code /v1/routing/config}、{@code /v1/cache/config} …），
 * 在这儿再放一个"万能写"等于给 6 个域各开第二个真源。
 * <p>
 * 不在数据面白名单里，因此 {@code security.enabled=true} 时读要令牌、写要写角色。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/runtime-config")
@Tag(name = "运行时配置", description = "六个配置域的落库快照、跨实例同步与恢复出厂")
public class AiRuntimeConfigController {

    private final AiGatewayProperties properties;

    private final RuntimeConfigService runtimeConfigService;

    private final RuntimeConfigPublisher runtimeConfigPublisher;

    @Operation(summary = "查询运行时配置",
            description = "persisted=false 表示本实例未接持久化（tenant.persistence 未开），配置只在本实例内存中生效")
    @GetMapping
    public Mono<Map<String, Object>> list() {
        return runtimeConfigService.describe().map(this::envelope);
    }

    @Operation(summary = "强制重载", description = "从数据库把六个域重放一遍；库不可用时保留当前内存值，source 如实反映取值来源")
    @PostMapping("/reload")
    public Mono<Map<String, Object>> reload() {
        return runtimeConfigService.loadAll().then(list());
    }

    @Operation(summary = "恢复出厂", description = "删掉该域的库中快照，回到 yml 值；其他实例在下一次轮询跟随")
    @DeleteMapping("/{domain}")
    public Mono<Map<String, Object>> reset(@PathVariable("domain") String domain) {
        return runtimeConfigPublisher.reset(resolve(domain)).then(list());
    }

    private Map<String, Object> envelope(List<Map<String, Object>> items) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("persisted", runtimeConfigService.persistable());
        response.put("pollIntervalSeconds", properties.getRuntimeConfig().getPollInterval().toSeconds());
        response.put("securityForced", RuntimeConfigSupport.securityForceEnabled());
        response.put("items", items);
        return response;
    }

    private RuntimeConfigDomain resolve(String domain) {
        try {
            return RuntimeConfigDomain.fromKey(domain);
        } catch (IllegalArgumentException ex) {
            String available = Arrays.stream(RuntimeConfigDomain.values())
                    .map(RuntimeConfigDomain::key)
                    .collect(Collectors.joining("/"));
            throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST,
                    "未知的配置域：" + domain + "，可用值 " + available);
        }
    }
}
