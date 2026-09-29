package com.nageoffer.shortlink.aigateway.persistence.repository;

import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderHealthEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * 渠道健康仓储。
 * <p>
 * <b>计数与状态迁移全部用原子 SQL，不做"读-改-写"。</b>两个原因：
 * <ol>
 *   <li>多实例并发探测时读-改-写会丢计数，"连败 5 次"可能永远到不了阈值；</li>
 *   <li>更隐蔽的是整行 {@code save()} 会覆盖掉读之后被别人改掉的其他列 ——
 *       比如运维刚在控制台把 {@code manual_disabled} 置 1，探测的整行写回会把它抹掉。</li>
 * </ol>
 * 条件更新（{@code WHERE status = 'UP'} / {@code WHERE status = 'DOWN'}）同时承担"并发下只有一个写成功"，
 * 返回值是受影响行数，调用方据此判断这次是否真的发生了状态迁移。
 * <p>
 * <b>所有写语句都带 {@code manual_disabled = 0} 守卫</b>（除手工设置它的那两个方法）：
 * 手动禁用的行探测不得触碰，否则运维的禁用会被下一轮探测覆盖。
 * <p>
 * 注意 {@code status} 的字面量在 SQL 里是硬编码的，与 {@link ProviderHealthEntity#STATUS_UP} /
 * {@link ProviderHealthEntity#STATUS_DOWN} 必须一致。
 */
public interface ProviderHealthRepository extends ReactiveCrudRepository<ProviderHealthEntity, Long> {

    @Override
    Flux<ProviderHealthEntity> findAll();

    Mono<ProviderHealthEntity> findByProvider(String provider);

    /**
     * 保证行存在（幂等）。第一次探测前先建行，后面的原子 UPDATE 才有行可改。
     * <p>
     * 初始状态取 UP + 零计数：只失败一两次不该禁用渠道，阈值判定交给 {@link #markDownIfThresholdReached}。
     */
    @Modifying
    @Query("INSERT IGNORE INTO provider_health (provider, status, consecutive_failures, consecutive_successes, last_checked_at)"
            + " VALUES (:provider, 'UP', 0, 0, NOW())")
    Mono<Integer> ensureRow(@Param("provider") String provider);

    /**
     * 记一次成功：连败清零、连胜 +1。返回 0 表示该行被手动禁用，本次结果被忽略。
     */
    @Modifying
    @Query("UPDATE provider_health"
            + " SET consecutive_successes = consecutive_successes + 1,"
            + "     consecutive_failures = 0,"
            + "     last_success_at = NOW(),"
            + "     last_checked_at = NOW(),"
            + "     last_error = NULL,"
            + "     latency_millis = :latency"
            + " WHERE provider = :provider AND manual_disabled = 0")
    Mono<Integer> recordSuccess(@Param("provider") String provider, @Param("latency") Long latency);

    /**
     * 记一次失败：连胜清零、连败 +1。返回 0 表示该行被手动禁用，本次结果被忽略。
     */
    @Modifying
    @Query("UPDATE provider_health"
            + " SET consecutive_failures = consecutive_failures + 1,"
            + "     consecutive_successes = 0,"
            + "     last_failure_at = NOW(),"
            + "     last_checked_at = NOW(),"
            + "     last_error = :error,"
            + "     latency_millis = :latency"
            + " WHERE provider = :provider AND manual_disabled = 0")
    Mono<Integer> recordFailure(@Param("provider") String provider,
                               @Param("error") String error,
                               @Param("latency") Long latency);

    /**
     * 连败达阈值且当前为 UP 才写 DOWN。返回受影响行数（0 或 1），1 表示这次真的发生了禁用。
     */
    @Modifying
    @Query("UPDATE provider_health"
            + " SET status = 'DOWN', disabled_until = :disabledUntil"
            + " WHERE provider = :provider"
            + "   AND manual_disabled = 0"
            + "   AND status = 'UP'"
            + "   AND consecutive_failures >= :failureThreshold")
    Mono<Integer> markDownIfThresholdReached(@Param("provider") String provider,
                                             @Param("failureThreshold") int failureThreshold,
                                             @Param("disabledUntil") LocalDateTime disabledUntil);

    /**
     * 连胜达恢复门槛且当前为 DOWN 才写回 UP，并把两个计数清零（新的一轮从干净状态开始）。
     * 返回受影响行数，1 表示这次真的发生了恢复。
     */
    @Modifying
    @Query("UPDATE provider_health"
            + " SET status = 'UP', consecutive_failures = 0, consecutive_successes = 0, disabled_until = NULL"
            + " WHERE provider = :provider"
            + "   AND manual_disabled = 0"
            + "   AND status = 'DOWN'"
            + "   AND consecutive_successes >= :recoveryThreshold")
    Mono<Integer> markUpIfRecovered(@Param("provider") String provider,
                                   @Param("recoveryThreshold") int recoveryThreshold);

    /**
     * 控制台手动禁用：只置 {@code manual_disabled}，不动 {@code status}。
     * 渠道立刻不可路由（可行性判定看 {@code status==DOWN || manual_disabled}），
     * 但保留探测结论，便于解释"它当时是怎么坏的"。
     */
    @Modifying
    @Query("UPDATE provider_health SET manual_disabled = 1 WHERE provider = :provider")
    Mono<Integer> markManuallyDisabled(@Param("provider") String provider);

    /**
     * 控制台手动恢复：<b>同时清掉自动结论</b>（status 回 UP、计数与冷却清零）。
     * 只把 {@code manual_disabled} 置 0 是不够的 —— 渠道若是被探测判 DOWN 的，
     * 运维点"启用"之后会发现它依然不可用，看起来像按钮失效。
     */
    @Modifying
    @Query("UPDATE provider_health"
            + " SET manual_disabled = 0, status = 'UP', consecutive_failures = 0,"
            + "     consecutive_successes = 0, disabled_until = NULL, last_error = NULL"
            + " WHERE provider = :provider")
    Mono<Integer> markManuallyEnabled(@Param("provider") String provider);
}
