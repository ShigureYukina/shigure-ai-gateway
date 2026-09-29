package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.probe.ChannelProbeService;
import com.nageoffer.shortlink.aigateway.probe.ProbeSummary;
import com.nageoffer.shortlink.aigateway.routing.ChannelHealthRegistry;
import com.nageoffer.shortlink.aigateway.routing.RouteCandidates;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 渠道健康管理面：探测结论、人工禁用/恢复、强制探测。
 * <p>
 * 与通道组（{@code /v1/routing/groups}）分开放：那边管"怎么组合与负载均衡"，
 * 这边管"这一刻它能不能用"，真源也不同（{@code provider_group} 三表 vs {@code provider_health} 单表）。
 * <p>
 * 三种"不可用"在响应里都是独立的布尔字段而不是一个枚举，因为它们是<b>并列</b>的：
 * yml 静态禁用（跟着发布走）、控制台人工禁用（运维临时动作）、探测结论（自动）。
 * 合并成一个 {@code status} 会让"yml 说是禁用、运维说已启用、探测说 UP"这种组合没法表达。
 * 另外每条渠道都带一个 {@code reason} 字符串，省得控制台自己拼话术。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/routing/channels/health")
@Tag(name = "渠道健康", description = "主动探测结论、自动禁用/恢复与人工开关")
public class AiChannelHealthController {

    private final ChannelProbeService channelProbeService;

    private final ChannelHealthRegistry channelHealthRegistry;

    private final AiGatewayProperties properties;

    @Operation(summary = "查询渠道健康",
            description = "返回每条渠道的探测计数、最近结论与不可用原因；degradation 说明当前有哪些降级")
    @GetMapping
    public Mono<Map<String, Object>> list() {
        // 控制台是低频人工请求，顺手读一次库让计数与最近结论是新鲜的。
        // 读失败不能把整个页面打掉：refresh 内部只在成功后替换快照，这里保留旧快照继续渲染。
        return channelHealthRegistry.refresh()
                .onErrorResume(ex -> {
                    log.warn("failed to refresh channel health for console, rendering last snapshot: {}", ex.getMessage());
                    return Mono.empty();
                })
                .then(Mono.fromSupplier(this::render));
    }

    @Operation(summary = "重新加载快照",
            description = "从数据库重读快照，不触发探测；用于其他实例刚改过状态时手工对齐")
    @PostMapping("/reload")
    public Mono<Map<String, Object>> reload() {
        return channelHealthRegistry.refresh()
                .then(Mono.fromSupplier(this::render));
    }

    @Operation(summary = "立即探测全部渠道",
            description = "强制跑一轮探测（跳过跨实例锁）并返回最新视图；连续失败达阈值的渠道会被自动禁用")
    @PostMapping("/probe")
    public Mono<Map<String, Object>> probe() {
        // 跳过跨实例锁：运维点了按钮就该立刻有结果，而不是"本轮被别的实例抢走了"。
        // 并发强制探测不会把状态改错 —— 状态迁移由 SQL 条件更新兜底。
        return channelProbeService.probeAll(true)
                .then(Mono.fromSupplier(this::render));
    }

    @Operation(summary = "人工启用/禁用一个渠道",
            description = "写入 manual_disabled 并广播版本号；禁用的渠道立刻退出候选集，重新启用会一并清掉自动探测结论")
    @PostMapping("/{provider}")
    public Mono<Map<String, Object>> toggle(@PathVariable("provider") String provider,
                                            @RequestBody(required = false) Map<String, Object> request) {
        Boolean enabled = parseEnabled(request);
        if (enabled == null) {
            return Mono.error(new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST,
                    "enabled 必填，且必须是布尔值（true 启用 / false 禁用）"));
        }
        if (!RouteCandidates.configured(properties).contains(provider)) {
            // 不在候选集里的渠道本来就不会被路由选中，给它写人工禁用只是往库里塞一条没人看的行
            return Mono.error(new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST,
                    "未知渠道：" + provider + "（只接受已配置 provider-base-url 的渠道）"));
        }
        return channelProbeService.setManualDisabled(provider, !enabled)
                .flatMap(persisted -> persisted
                        ? Mono.fromSupplier(this::render)
                        : Mono.error(new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST,
                                "渠道状态未生效：本实例未接持久化或写库失败，人工禁用无处存放")));
    }

    /**
     * 渲染当前快照。<b>不做任何 IO</b>，调用方负责先把快照刷到最新。
     * <p>
     * 渠道清单取"已配置 ∪ 快照里有行的"：只取已配置会漏掉那些被人为改过配置后残留的行
     * （运维需要看到它们并知道该清理）；只取快照会漏掉"还没探过"的渠道（而那正是最该显示的一类：
     * 它一直没被探到，说明探测本身有问题）。
     */
    private Map<String, Object> render() {
        Set<String> configured = RouteCandidates.configured(properties);
        Map<String, ChannelHealthRegistry.ChannelHealth> snapshot = channelHealthRegistry.snapshot();
        Set<String> providers = new LinkedHashSet<>(configured);
        providers.addAll(snapshot.keySet());

        List<Map<String, Object>> items = new ArrayList<>();
        for (String provider : providers) {
            items.add(item(provider, snapshot.get(provider), configured.contains(provider)));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", channelHealthRegistry.source());
        response.put("degradation", degradation());
        response.put("lastProbe", lastProbe());
        response.put("items", items);
        return response;
    }

    private Map<String, Object> item(String provider,
                                     ChannelHealthRegistry.ChannelHealth health,
                                     boolean configured) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("provider", provider);
        item.put("configured", configured);
        item.put("usable", channelHealthRegistry.allows(provider));
        item.put("staticDisabled", Boolean.FALSE.equals(
                properties.getProbe().getChannelEnabled().get(provider)));
        item.put("manualDisabled", health != null && health.manualDisabled());
        item.put("down", health != null && health.down());
        item.put("consecutiveFailures", health == null ? 0 : health.consecutiveFailures());
        item.put("consecutiveSuccesses", health == null ? 0 : health.consecutiveSuccesses());
        item.put("lastError", health == null ? null : health.lastError());
        item.put("lastCheckedAt", text(health == null ? null : health.lastCheckedAt()));
        item.put("disabledUntil", text(health == null ? null : health.disabledUntil()));
        item.put("latencyMillis", health == null ? null : health.latencyMillis());
        item.put("reason", reason(provider, health, configured));
        return item;
    }

    /**
     * 为什么不可用。可用时返回 null。
     * <p>
     * 顺序与 {@link ChannelHealthRegistry#allows} 的判定顺序一致，保证展示的原因就是真正的那个原因。
     */
    private String reason(String provider,
                          ChannelHealthRegistry.ChannelHealth health,
                          boolean configured) {
        if (Boolean.FALSE.equals(properties.getProbe().getChannelEnabled().get(provider))) {
            return "yml 静态禁用（probe.channel-enabled." + provider + "=false）";
        }
        if (health != null && health.manualDisabled()) {
            return "控制台人工禁用";
        }
        if (health != null && health.down()) {
            return health.cooldownElapsed()
                    ? "探测判定不可用，冷却已过，下一轮探测会重试"
                    : "探测判定不可用，冷却至 " + text(health.disabledUntil());
        }
        if (!configured) {
            return "未配置 provider-base-url，不会被路由选中";
        }
        return null;
    }

    private Map<String, Object> degradation() {
        boolean persistenceAvailable = channelHealthRegistry.databaseBacked();
        boolean redisAvailable = !channelProbeService.redisDegraded();
        List<String> notes = new ArrayList<>();
        if (!persistenceAvailable) {
            notes.add("本实例未接持久化：人工禁用只存在于内存，重启即失效，也不会同步到其他实例");
        }
        if (!redisAvailable) {
            notes.add("Redis 不可用：跨实例探测去重已失效，各实例会各自探测（状态迁移仍由 SQL 条件更新兜底）");
        }
        Map<String, Object> degradation = new LinkedHashMap<>();
        degradation.put("persistenceAvailable", persistenceAvailable);
        degradation.put("redisAvailable", redisAvailable);
        degradation.put("notes", notes);
        return degradation;
    }

    private Map<String, Object> lastProbe() {
        ProbeSummary summary = channelProbeService.lastSummary();
        if (summary == null) {
            // 还没跑过第一轮（启动后头 initialDelay 内），显式返回 null 而不是空对象：
            // 空对象看起来像"探过了但什么都没探到"
            return null;
        }
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("finishedAt", text(summary.finishedAt()));
        probe.put("probed", summary.probed());
        probe.put("healthy", summary.healthy());
        probe.put("unhealthy", summary.unhealthy());
        probe.put("inconclusive", summary.inconclusive());
        probe.put("skipped", summary.skipped());
        probe.put("disabled", summary.disabled());
        probe.put("recovered", summary.recovered());
        probe.put("skippedByLock", summary.skippedByLock());
        return probe;
    }

    /**
     * 接受布尔与布尔字符串两种写法：控制台表单提交过来的常常是字符串。
     */
    private static Boolean parseEnabled(Map<String, Object> request) {
        if (request == null) {
            return null;
        }
        Object value = request.get("enabled");
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(text)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
