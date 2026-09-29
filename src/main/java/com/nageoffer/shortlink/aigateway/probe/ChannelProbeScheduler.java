package com.nageoffer.shortlink.aigateway.probe;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 渠道探测的定时入口。
 * <p>
 * 逻辑一行都没有：探测的编排全在 {@link ChannelProbeService}，这里只负责"什么时候跑"。
 * 分开是因为定时器不能进单测（要么真等，要么反射改注解），把调度与逻辑混在一起会让
 * 那部分逻辑一起变得不可测。
 * <p>
 * <b>占位符必须写全前缀</b>（{@code ${short-link.ai-gateway.probe.interval:...}}）。
 * {@code @Scheduled} 的占位符不做配置前缀补全，写成 {@code ${probe.interval}} 会在启动时
 * 解析不到而直接失败 —— 本项目已经踩过一次（见 {@code GatewaySyncScheduler}）。
 * 值必须是 ISO-8601（{@code PT5M}），写成 {@code 5m} 同样启动失败。
 * <p>
 * {@code enabled=false} 时整个 Bean 不注册，因此方法体里不再重复判一次开关。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "short-link.ai-gateway.probe", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ChannelProbeScheduler {

    private final ChannelProbeService channelProbeService;

    /**
     * {@code fixedDelay} 而不是 {@code fixedRate}：探测耗时不确定（多渠道路径下最坏
     * = 渠道数 × 单次超时），固定间隔会在上一轮没跑完时叠加新轮次。
     * 间隔即"上一轮结束到下一轮开始"，天然不重入（{@code ChannelProbeService} 里还有一层
     * {@code AtomicBoolean} 兜手工触发与调度撞车）。
     */
    @Scheduled(initialDelayString = "${short-link.ai-gateway.probe.initial-delay:PT60S}",
            fixedDelayString = "${short-link.ai-gateway.probe.interval:PT5M}")
    public void probe() {
        channelProbeService.probeAll()
                .subscribe(summary -> {
                    if (summary.skippedByLock() && log.isDebugEnabled()) {
                        log.debug("channel probe skipped: another instance is probing or this one is already running");
                    }
                }, ex -> log.warn("channel probe round failed: {}", ex.getMessage()));
    }
}
