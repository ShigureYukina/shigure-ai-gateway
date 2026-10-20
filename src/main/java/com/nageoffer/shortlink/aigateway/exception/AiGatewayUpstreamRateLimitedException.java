package com.nageoffer.shortlink.aigateway.exception;

/**
 * 上游以「限流」（HTTP 429）拒绝了本次调用。
 * <p>
 * 与 {@link AiGatewayUpstreamException} 同为上游侧错误、同样可重试，单独成类是为了让熔断器
 * 能把它排除在失败率之外（见 {@code application.yml} 的 {@code ignore-exceptions}）：429 是上游
 * 按配额临时踩刹车，不代表这条通道"坏了"。把它和 5xx/超时记进同一个失败率，会让一次瞬时
 * 限流在几秒内把整条渠道摘掉（见 {@code docs/load/key-isolation.md}）。
 * <p>
 * 判定口径与后台健康探测一致——探测同样不计 429（{@code ChannelProbeService}）。
 * 排除的只是熔断器的失败率记账；回退判定与健康分仍按"通道故障"处理（{@code UpstreamCallExecutor.isProviderChannelFault}）。
 */
public class AiGatewayUpstreamRateLimitedException extends AiGatewayUpstreamException {

    public AiGatewayUpstreamRateLimitedException(Integer status, String message, boolean retriable) {
        super(status, message, retriable);
    }
}
