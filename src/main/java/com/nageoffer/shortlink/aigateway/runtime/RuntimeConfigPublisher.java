package com.nageoffer.shortlink.aigateway.runtime;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 控制台配置写入口的统一收尾：内存生效 → 落库 → 失败回滚。
 * <p>
 * <b>调用约定</b>：调用方<b>先</b>把值改到 {@link AiGatewayProperties} 上，再调这里。这样
 * "没有 DB 的本地环境"与"有 DB 的多实例环境"走同一条代码路径，不必为两种模式各写一套。
 * 代价是写失败时内存已经被改过了，所以回滚必须由这里负责（{@link RuntimeConfigService#rollback}）。
 * <p>
 * 响应里始终带上 {@code persisted} 与 {@code version}：控制台据此提示"已同步到全部实例"
 * 还是"仅本实例生效"。没有 repository 时 {@code persisted=false} 且<b>不报错</b>——
 * 本地起一个实例调配置是正常用法，不该被当成失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeConfigPublisher {

    private final AiGatewayProperties properties;

    /**
     * 为 {@code null} 时表示"没有持久化能力"：所有写操作只改本实例内存。
     * <p>
     * 生产装配一定拿得到（{@code RuntimeConfigService} 是单例 Bean），null 只出现在
     * {@link #noPersistence()} 造出来的实例上——给不接 DB 的单测与只想保留老构造签名的 controller 用。
     */
    private final RuntimeConfigService runtimeConfigService;

    /**
     * 无持久化能力的发布器：控制台写入只改本实例内存，响应里 {@code persisted=false}。
     */
    public static RuntimeConfigPublisher noPersistence() {
        return new RuntimeConfigPublisher(null, null);
    }

    /**
     * @param domain 被写的配置域
     * @param view   控制台要展示的响应体（通常就是该 controller 原来的 config() 结果），会被补上
     *               {@code domain} / {@code persisted} / {@code version} 三个字段后返回
     */
    public Mono<Map<String, Object>> save(RuntimeConfigDomain domain, Map<String, Object> view) {
        if (runtimeConfigService == null) {
            return Mono.just(decorate(domain, view, false, 0L));
        }
        // 整段包在 defer 里：extract / JSON 序列化都是同步调用，抛出来会绕过下面的 onErrorResume，
        // 那样内存已经被 controller 改过却没有人回滚。
        return Mono.defer(() -> {
            Map<String, Object> snapshot = RuntimeConfigSupport.extract(domain, properties);
            return runtimeConfigService.persist(domain, snapshot)
                    .map(token -> decorate(domain, view, true, token))
                    .switchIfEmpty(Mono.fromSupplier(() -> decorate(domain, view, false, 0L)))
                    .onErrorResume(ex -> {
                        runtimeConfigService.rollback(domain);
                        log.warn("failed to persist runtime config domain={}, rolled back in-memory values: {}",
                                domain.key(), ex.getMessage());
                        return Mono.error(new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST,
                                "配置未生效：写库失败，已回滚为上一次成功生效的值（" + ex.getMessage() + "）"));
                    });
        });
    }

    /**
     * 把"这个域当前生效值"抹掉，回到 yml 基线，并通知其他实例。
     */
    public Mono<Void> reset(RuntimeConfigDomain domain) {
        if (runtimeConfigService == null) {
            return Mono.empty();
        }
        return runtimeConfigService.delete(domain);
    }

    private Map<String, Object> decorate(RuntimeConfigDomain domain, Map<String, Object> view, boolean persisted, long version) {
        // view 常常是 Map.of(...) 造的不可变 Map，必须复制一份再补字段
        Map<String, Object> response = new LinkedHashMap<>();
        if (view != null) {
            response.putAll(view);
        }
        response.put("domain", domain.key());
        response.put("persisted", persisted);
        response.put("version", version);
        return response;
    }
}
