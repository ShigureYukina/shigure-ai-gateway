package com.nageoffer.shortlink.aigateway.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 配置刷新后的对账桥：外部配置源（Nacos）变更 → RefreshEvent → 整体重绑，这一步会把
 * 运行时配置中心先前写进 {@code AiGatewayProperties} 的 DB 覆盖一并冲掉。
 * 本监听器在每次 {@link EnvironmentChangeEvent} 后把落过库的域重新 apply，
 * 落实取值优先级：<b>环境变量 kill-switch &gt; DB &gt; Nacos &gt; yml</b>。
 * <p>
 * 两条已知边界（都是预期行为，不是缺陷）：
 * <ul>
 *   <li>没落库的域：Nacos 新值在重绑后直接生效，这里不碰 —— 否则等于把刷新结果回滚。</li>
 *   <li>没有 DB 持久化的实例（{@code tenant.persistence.enabled=false}）：控制台的内存改动
 *       本来就不跨实例、不跨重启，配置源刷新后重置为配置源的值。</li>
 * </ul>
 * <p>
 * 刻意用 {@link EnvironmentChangeEvent} 而不是监听 Nacos 客户端回调：刷新可能来自任何
 * 配置源，桥只关心"内存刚被重置过"这个结果，不关心是谁触发的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NacosConfigRefreshBridge implements ApplicationListener<EnvironmentChangeEvent> {

    private final RuntimeConfigService runtimeConfigService;

    @Override
    public void onApplicationEvent(EnvironmentChangeEvent event) {
        if (!runtimeConfigService.persistable()) {
            return;
        }
        runtimeConfigService.reapplyDatabaseOverrides()
                .transform(this::logFailure)
                .subscribe();
    }

    private Mono<Void> logFailure(Mono<Void> source) {
        return source.onErrorResume(ex -> {
            log.warn("failed to reapply runtime config overrides after refresh, keeping rebound values: {}",
                    ex.getMessage());
            return Mono.empty();
        });
    }
}
