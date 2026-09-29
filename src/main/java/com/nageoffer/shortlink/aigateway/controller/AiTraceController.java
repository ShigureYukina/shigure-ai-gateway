package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.observability.AiRequestTraceBus;
import com.nageoffer.shortlink.aigateway.observability.AiRequestTraceEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时请求链路。
 * <p>
 * 用 SSE 而不是 WebSocket：链路事件是单向的，SSE 天然是 HTTP、能过网关与代理、
 * 浏览器端一个 {@code EventSource} 就能接，运维成本最低。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/trace")
@Tag(name = "实时链路", description = "请求各阶段事件的实时推送与近期回看")
public class AiTraceController {

    private final AiRequestTraceBus requestTraceBus;

    @Operation(summary = "实时链路事件流", description = "SSE 推送 routing/cache/quota/upstream/first-token/completed 各阶段事件")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AiRequestTraceEvent>> stream() {
        // 心跳：长时间没有流量时也让前端知道连接还活着，同时防止中间代理掐断空闲连接
        Flux<ServerSentEvent<AiRequestTraceEvent>> heartbeat = Flux.interval(Duration.ofSeconds(20))
                .map(tick -> ServerSentEvent.<AiRequestTraceEvent>builder().comment("keep-alive").build());
        return Flux.merge(
                requestTraceBus.stream().map(event -> ServerSentEvent.builder(event).event("trace").build()),
                heartbeat);
    }

    @Operation(summary = "近期链路事件", description = "返回最近的事件快照，供页面首屏铺满")
    @GetMapping("/recent")
    public Map<String, Object> recent(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<AiRequestTraceEvent> events = requestTraceBus.recent(limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subscribers", requestTraceBus.subscriberCount());
        result.put("count", events.size());
        result.put("events", events);
        return result;
    }
}
