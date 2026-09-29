package com.nageoffer.shortlink.aigateway.governance;

import lombok.RequiredArgsConstructor;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式响应用量累加器。
 * <p>
 * 流式链路是透传的，没有"整体响应体"可供事后解析，因此需要在 chunk 经过时增量收集。
 * 每次请求创建一个实例（非 Spring Bean），状态天然按请求隔离。
 */
@RequiredArgsConstructor
public class StreamUsageCollector {

    private final UsageExtractor usageExtractor;

    private final AtomicReference<UsageDetail> usage = new AtomicReference<>();

    private volatile boolean doneSeen;

    /**
     * 接收一个 SSE 数据负载，累计其中的用量信息。
     */
    public void accept(String payload) {
        if (usageExtractor.isStreamDone(payload)) {
            doneSeen = true;
            return;
        }
        UsageDetail detail = usageExtractor.extractStreamChunkUsage(payload);
        if (detail != null) {
            usage.set(detail);
        }
    }

    public UsageDetail usage() {
        return usage.get();
    }

    /**
     * 上游是否已经下发过结束标记。未下发时由网关补一个，保证客户端能正常收流。
     */
    public boolean doneSeen() {
        return doneSeen;
    }
}
