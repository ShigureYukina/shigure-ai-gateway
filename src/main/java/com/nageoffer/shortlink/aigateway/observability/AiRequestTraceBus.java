package com.nageoffer.shortlink.aigateway.observability;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 实时链路事件总线。
 * <p>
 * 两个刻意的设计：
 * <ol>
 *   <li>没人订阅时直接返回，常态流量不为"可能有人在看"付出任何代价；</li>
 *   <li>用多播直投而不是缓冲队列——实时看板要的是"现在发生了什么"，
 *       给新订阅者重放历史既没意义，又会把内存拖成无界队列。</li>
 * </ol>
 * 另保留一个固定长度的近期环形缓冲，供页面刚打开时先铺一屏。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRequestTraceBus {

    private static final int RECENT_LIMIT = 200;

    private final AiGatewayProperties properties;

    private final Sinks.Many<AiRequestTraceEvent> sink = Sinks.many().multicast().directBestEffort();

    private final Deque<AiRequestTraceEvent> recent = new ArrayDeque<>(RECENT_LIMIT);

    /**
     * 发布一个阶段事件。永不阻塞、永不抛错：观测能力不能反过来影响请求。
     */
    public void publish(AiRequestTraceEvent event) {
        if (event == null || !properties.getObservability().isTraceStreamEnabled()) {
            return;
        }
        if (event.getTimestamp() == null) {
            event.setTimestamp(System.currentTimeMillis());
        }
        remember(event);
        if (sink.currentSubscriberCount() == 0) {
            return;
        }
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.debug("dropped trace event: requestId={}, stage={}, reason={}",
                    event.getRequestId(), event.getStage(), result);
        }
    }

    public Flux<AiRequestTraceEvent> stream() {
        return sink.asFlux();
    }

    public int subscriberCount() {
        return sink.currentSubscriberCount();
    }

    /**
     * 最近的事件快照，供页面首屏加载。
     */
    public List<AiRequestTraceEvent> recent(int limit) {
        int normalized = Math.min(Math.max(limit, 1), RECENT_LIMIT);
        synchronized (recent) {
            List<AiRequestTraceEvent> snapshot = new ArrayList<>(recent);
            int from = Math.max(0, snapshot.size() - normalized);
            return List.copyOf(snapshot.subList(from, snapshot.size()));
        }
    }

    private void remember(AiRequestTraceEvent event) {
        synchronized (recent) {
            if (recent.size() >= RECENT_LIMIT) {
                recent.pollFirst();
            }
            recent.addLast(event);
        }
    }
}
