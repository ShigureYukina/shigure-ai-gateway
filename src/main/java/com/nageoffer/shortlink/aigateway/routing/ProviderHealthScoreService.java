package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayRoutingProperties;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsKeys;
import com.nageoffer.shortlink.aigateway.observability.CostEstimator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class ProviderHealthScoreService {

    private static final double MAX_LATENCY_THRESHOLD = 10_000D;

    private static final double MAX_COST_THRESHOLD = 0.10D;

    private static final long MIN_REQUIRED_CALLS = 5L;

    private final StringRedisTemplate stringRedisTemplate;

    private final AiGatewayProperties properties;

    private final CostEstimator costEstimator;

    private final ChannelHealthView channelHealthView;

    @Autowired
    public ProviderHealthScoreService(StringRedisTemplate stringRedisTemplate,
                                      AiGatewayProperties properties,
                                      CostEstimator costEstimator,
                                      ChannelHealthView channelHealthView) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.costEstimator = costEstimator;
        this.channelHealthView = channelHealthView;
    }

    /**
     * 不做健康过滤的构造方式：等价于探测未启用时的旧行为。
     * <p>
     * 保留它不是为了兼容测试而已 —— 它明确表达了"这个实例不消费渠道健康状态"，
     * 也让历史调用点（{@code ProviderGroupService} 的便捷构造）不必被迫构造一个注册表。
     */
    public ProviderHealthScoreService(StringRedisTemplate stringRedisTemplate,
                                      AiGatewayProperties properties,
                                      CostEstimator costEstimator) {
        this(stringRedisTemplate, properties, costEstimator, ChannelHealthView.allowAll());
    }

    public List<ProviderHealthScore> getProviderScores(String model) {
        String hour = AiGatewayMetricsKeys.currentHour();
        List<ProviderHealthScore> result = new ArrayList<>();
        for (String provider : resolveCandidateProviders()) {
            ProviderMetrics metrics = loadProviderMetrics(provider, model, hour);
            if (metrics.callCount() < MIN_REQUIRED_CALLS) {
                continue;
            }
            result.add(toHealthScore(provider, metrics));
        }
        result.sort(Comparator.comparing(ProviderHealthScore::getHealthScore).reversed()
                .thenComparing(ProviderHealthScore::getSuccessRate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ProviderHealthScore::getAvgLatencyMillis, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ProviderHealthScore::getCostPerCall, Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    public String getBestProvider(String model) {
        List<ProviderHealthScore> scores = getProviderScores(model);
        if (scores.isEmpty()) {
            return null;
        }
        AiGatewayRoutingProperties.RoutingStrategy strategy = properties.getRouting().getRoutingStrategy();
        ProviderHealthScore selected = switch (strategy) {
            case COST_OPTIMIZED -> scores.stream()
                    .min(Comparator.comparing(ProviderHealthScore::getCostPerCall, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(ProviderHealthScore::getHealthScore, Comparator.nullsLast(Comparator.reverseOrder())))
                    .orElse(scores.get(0));
            case LATENCY_OPTIMIZED -> scores.stream()
                    .min(Comparator.comparing(ProviderHealthScore::getAvgLatencyMillis, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(ProviderHealthScore::getHealthScore, Comparator.nullsLast(Comparator.reverseOrder())))
                    .orElse(scores.get(0));
            case DYNAMIC, STATIC -> scores.get(0);
        };
        return selected.getProvider();
    }

    /**
     * 记录一次 provider 调用结果（健康分排序的输入）。
     * <p>
     * 写入键由 provider 与实际上游模型组成（{@code provider:model} 作为 model 段），与
     * {@link #loadProviderMetrics} 的读取口径保持一致，键布局统一由 {@link AiGatewayMetricsKeys} 决定；
     * 成本由 token 用量实时估算，失败调用没有用量自然计 0。
     * <p>
     * 底层是同步 Redis 客户端，响应式链路请使用 {@link #recordProviderMetricsAsync}。
     */
    public void recordProviderMetrics(String provider, String model, long latencyMillis, boolean success, long tokenIn, long tokenOut) {
        if (!StringUtils.hasText(provider) || !StringUtils.hasText(model)) {
            return;
        }
        String hour = AiGatewayMetricsKeys.currentHour();
        String metricModel = AiGatewayMetricsKeys.providerModel(provider, model);
        String metricKey = AiGatewayMetricsKeys.modelMetricKey(metricModel, hour);
        String latencyKey = AiGatewayMetricsKeys.latencyKey(metricModel, hour);
        stringRedisTemplate.opsForHash().increment(metricKey, AiGatewayMetricsKeys.FIELD_CALLS, 1);
        if (success) {
            stringRedisTemplate.opsForHash().increment(metricKey, AiGatewayMetricsKeys.FIELD_SUCCESS, 1);
        }
        stringRedisTemplate.opsForHash().increment(metricKey, AiGatewayMetricsKeys.FIELD_COST,
                // 成本在这里现算、不向调用方要：调用明细侧（AiGatewayMetricsRecorder）也会独立估算一次，
                // 两边是同一个纯查表函数、同样的入参，结果必然一致，不值得为此改 recordOutcome 的签名。
                costEstimator.estimate(model, tokenIn, tokenOut));
        stringRedisTemplate.expire(metricKey, AiGatewayMetricsKeys.METRIC_TTL);

        String latencyMember = AiGatewayMetricsKeys.latencyMember(metricModel, System.currentTimeMillis());
        stringRedisTemplate.opsForZSet().add(latencyKey, latencyMember, latencyMillis);
        stringRedisTemplate.expire(latencyKey, AiGatewayMetricsKeys.METRIC_TTL);
    }

    /**
     * 非阻塞上报入口：同步写入放到弹性线程池执行，避免在 Netty 事件循环上阻塞。
     * <p>
     * 健康分只是路由的参考信号，写失败不能影响已经完成的用户请求，因此只记日志。
     */
    public void recordProviderMetricsAsync(String provider, String model, long latencyMillis, boolean success, long tokenIn, long tokenOut) {
        Mono.fromRunnable(() -> recordProviderMetrics(provider, model, latencyMillis, success, tokenIn, tokenOut))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(ignored -> {
                }, ex -> log.warn("failed to record provider health metrics: provider={}, model={}", provider, model, ex));
    }

    private ProviderHealthScore toHealthScore(String provider, ProviderMetrics metrics) {
        double normalizedLatency = Math.min(metrics.avgLatencyMillis() / MAX_LATENCY_THRESHOLD, 1.0D);
        double normalizedCost = Math.min(metrics.costPerCall() / MAX_COST_THRESHOLD, 1.0D);
        double score = metrics.successRate() * 40D + (1 - normalizedLatency) * 30D + (1 - normalizedCost) * 30D;
        return ProviderHealthScore.builder()
                .provider(provider)
                .model(metrics.model())
                .healthScore((int) Math.round(Math.max(0D, Math.min(100D, score))))
                .avgLatencyMillis(Math.round(metrics.avgLatencyMillis()))
                .successRate(metrics.successRate())
                .costPerCall(metrics.costPerCall())
                .lastUpdated(metrics.lastUpdated())
                .build();
    }

    private ProviderMetrics loadProviderMetrics(String provider, String model, String hour) {
        String providerMetricModel = AiGatewayMetricsKeys.providerModel(provider, model);
        ProviderMetrics directMetrics = loadMetrics(providerMetricModel, hour, provider, model);
        if (directMetrics.callCount() > 0) {
            return directMetrics;
        }
        // 兜底：读未限定 provider 的聚合值（由 AiGatewayMetricsRecorder 写入）。两边共用
        // AiGatewayMetricsKeys 的字段名常量，正是为了不在这里静默读到 0。
        String providerModel = resolveProviderModel(provider, model);
        return loadMetrics(providerModel, hour, provider, providerModel);
    }

    private ProviderMetrics loadMetrics(String metricModel, String hour, String provider, String resolvedModel) {
        String metricKey = AiGatewayMetricsKeys.modelMetricKey(metricModel, hour);
        String latencyKey = AiGatewayMetricsKeys.latencyKey(metricModel, hour);
        long callCount = toLong(stringRedisTemplate.opsForHash().get(metricKey, AiGatewayMetricsKeys.FIELD_CALLS));
        long successCount = toLong(stringRedisTemplate.opsForHash().get(metricKey, AiGatewayMetricsKeys.FIELD_SUCCESS));
        double totalCost = toDouble(stringRedisTemplate.opsForHash().get(metricKey, AiGatewayMetricsKeys.FIELD_COST));
        AvgLatencySnapshot avgLatencySnapshot = resolveAverageLatency(latencyKey);
        double successRate = callCount == 0 ? 0D : successCount * 1.0D / callCount;
        double costPerCall = callCount == 0 ? 0D : totalCost / callCount;
        return new ProviderMetrics(
                provider,
                resolvedModel,
                callCount,
                successRate,
                costPerCall,
                avgLatencySnapshot.avgLatencyMillis(),
                avgLatencySnapshot.lastUpdated()
        );
    }

    private AvgLatencySnapshot resolveAverageLatency(String latencyKey) {
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet().rangeWithScores(latencyKey, 0, -1);
        if (tuples == null || tuples.isEmpty()) {
            return new AvgLatencySnapshot(0D, Instant.now());
        }
        double total = 0D;
        long count = 0L;
        Instant lastUpdated = Instant.now();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            Double score = tuple.getScore();
            if (score != null) {
                total += score;
                count++;
            }
            Instant tupleTime = extractInstant(tuple.getValue());
            if (tupleTime.isAfter(lastUpdated)) {
                lastUpdated = tupleTime;
            }
        }
        return new AvgLatencySnapshot(count == 0 ? 0D : total / count, lastUpdated);
    }

    /**
     * latency zset 成员的时间戳格式由 {@link AiGatewayMetricsKeys#latencyMember} 约定，
     * 不可解析时按"刚更新过"处理，与历史行为一致。
     */
    private Instant extractInstant(String memberValue) {
        Long epochMillis = AiGatewayMetricsKeys.latencyMemberTimestamp(memberValue);
        return epochMillis == null ? Instant.now() : Instant.ofEpochMilli(epochMillis);
    }

    private Set<String> resolveCandidateProviders() {
        // 被探测判为不可用（或人工禁用）的渠道不进候选集：动态路由不该把一个已经坏掉的渠道
        // 排到第一名，然后再由回退链去发现它不行。
        return RouteCandidates.configured(properties, channelHealthView);
    }

    private String resolveProviderModel(String provider, String clientModel) {
        return ModelAliasResolver.modelNameOn(properties.getUpstream().getModelAlias(), clientModel, provider);
    }

    private long toLong(Object value) {
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }

    private double toDouble(Object value) {
        return value == null ? 0D : Double.parseDouble(String.valueOf(value));
    }

    private record ProviderMetrics(
            String provider,
            String model,
            long callCount,
            double successRate,
            double costPerCall,
            double avgLatencyMillis,
            Instant lastUpdated
    ) {
    }

    private record AvgLatencySnapshot(double avgLatencyMillis, Instant lastUpdated) {
    }
}
