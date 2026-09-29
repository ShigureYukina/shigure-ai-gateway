package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.dto.req.ProviderGroupBindReqDTO;
import com.nageoffer.shortlink.aigateway.dto.req.ProviderGroupSaveReqDTO;
import com.nageoffer.shortlink.aigateway.routing.ProviderGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 通道组管理：把多个 provider 组成一个可负载均衡的组，并把模型绑定到组上。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/routing/groups")
@Tag(name = "通道组", description = "通道分组、负载均衡策略与模型绑定")
public class AiProviderGroupController {

    private final ProviderGroupService providerGroupService;

    @Operation(summary = "查询通道组", description = "返回全部通道组、负载均衡策略、成员权重与模型绑定，并标注数据来源（database/yml）")
    @GetMapping
    public Map<String, Object> list() {
        return providerGroupService.describe();
    }

    @Operation(summary = "保存通道组", description = "新建或覆盖一个通道组，成员整体替换")
    @PostMapping
    public Mono<Map<String, Object>> save(@RequestBody ProviderGroupSaveReqDTO request) {
        List<ProviderGroupService.MemberSpec> members = request.getMembers() == null
                ? List.of()
                : request.getMembers().stream()
                .map(member -> new ProviderGroupService.MemberSpec(member.getProvider(), member.getModel(),
                        member.getWeight(), member.getPriority()))
                .toList();
        return providerGroupService.saveGroup(request.getGroupName(), request.getStrategy(),
                request.getEnabled(), request.getDescription(), members);
    }

    @Operation(summary = "删除通道组", description = "删除组并解除其上的模型绑定")
    @DeleteMapping("/{groupName}")
    public Mono<Void> delete(@PathVariable("groupName") String groupName) {
        return providerGroupService.deleteGroup(groupName);
    }

    @Operation(summary = "绑定模型", description = "把一个或多个模型绑定到该组，一个模型只归属一个组")
    @PostMapping("/{groupName}/bindings")
    public Mono<Map<String, Object>> bind(@PathVariable("groupName") String groupName,
                                          @RequestBody ProviderGroupBindReqDTO request) {
        return providerGroupService.bindModels(groupName, request.getModels());
    }

    @Operation(summary = "解绑模型", description = "解除单个模型的组绑定")
    @DeleteMapping("/bindings")
    public Mono<Map<String, Object>> unbind(@RequestParam("model") String model) {
        return providerGroupService.unbindModel(model);
    }

    @Operation(summary = "重新加载通道组", description = "从数据库重新加载内存快照")
    @PostMapping("/reload")
    public Mono<Map<String, Object>> reload() {
        return providerGroupService.refresh().then(Mono.fromSupplier(providerGroupService::describe));
    }

    @Operation(summary = "通道组路由预览", description = "按模型预览命中的组、策略与通道顺序")
    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam("model") String model) {
        return providerGroupService.preview(model);
    }
}
