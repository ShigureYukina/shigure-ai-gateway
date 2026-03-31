package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProviderHealthScoreService {

    private static final String METRIC_KEY_PREFIX = "short-link:ai-gateway:metric:model:";

    private static final String LATENCY_KEY_PREFIX = "short-link:ai-gateway:metric:latency:";

    private static final double MAX_LATENCY_THRESHOLD = 10_000D;

    private static final double MAX_COST_THRESHOLD = 0.10D;

    private static final long MIN_REQUIRED_CALLS = 5L;

    private final StringRedisTemplate stringRedisTemplate;

    private final AiGatewayProperties properties;

    public List<ProviderHealthScore> getProviderScores(String model) {
        String hour = currentHour();
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
        AiGatewayProperties.RoutingStrategy strategy = properties.getRouting().getRoutingStrategy();
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

    public void recordProviderMetrics(String provider, String model, long latencyMillis, boolean success, double costPerCall) {
        if (!StringUtils.hasText(provider) || !StringUtils.hasText(model)) {
            return;
        }
        String hour = currentHour();
        String metricKey = metricKey(providerMetricModel(provider, model), hour);
        String latencyKey = latencyKey(providerMetricModel(provider, model), hour);
        stringRedisTemplate.opsForHash().increment(metricKey, "calls", 1);
        if (success) {
            stringRedisTemplate.opsForHash().increment(metricKey, "success", 1);
        }
        stringRedisTemplate.opsForHash().increment(metricKey, "cost", costPerCall);
        stringRedisTemplate.expire(metricKey, Duration.ofDays(2));

        String latencyMember = provider + ":" + model + ":" + System.currentTimeMillis();
        stringRedisTemplate.opsForZSet().add(latencyKey, latencyMember, latencyMillis);
        stringRedisTemplate.expire(latencyKey, Duration.ofDays(2));
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
        String providerMetricModel = providerMetricModel(provider, model);
        ProviderMetrics directMetrics = loadMetrics(providerMetricModel, hour, provider, model);
        if (directMetrics.callCount() > 0) {
            return directMetrics;
        }
        String providerModel = resolveProviderModel(provider, model);
        return loadMetrics(providerModel, hour, provider, providerModel);
    }

    private ProviderMetrics loadMetrics(String metricModel, String hour, String provider, String resolvedModel) {
        String metricKey = metricKey(metricModel, hour);
        String latencyKey = latencyKey(metricModel, hour);
        long callCount = toLong(stringRedisTemplate.opsForHash().get(metricKey, "calls"));
        long successCount = toLong(stringRedisTemplate.opsForHash().get(metricKey, "success"));
        double totalCost = toDouble(stringRedisTemplate.opsForHash().get(metricKey, "cost"));
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

    private Instant extractInstant(String memberValue) {
        if (!StringUtils.hasText(memberValue)) {
            return Instant.now();
        }
        int lastSeparator = memberValue.lastIndexOf(':');
        if (lastSeparator < 0 || lastSeparator == memberValue.length() - 1) {
            return Instant.now();
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(memberValue.substring(lastSeparator + 1)));
        } catch (NumberFormatException ignored) {
            return Instant.now();
        }
    }

    private Set<String> resolveCandidateProviders() {
        Set<String> providers = new LinkedHashSet<>();
        List<String> priority = properties.getRouting().getProviderPriority();
        if (priority != null) {
            providers.addAll(priority);
        }
        Map<String, String> providerBaseUrl = properties.getUpstream().getProviderBaseUrl();
        if (providerBaseUrl != null) {
            providers.addAll(providerBaseUrl.keySet());
        }
        if (StringUtils.hasText(properties.getUpstream().getDefaultProvider())) {
            providers.add(properties.getUpstream().getDefaultProvider());
        }
        return providers;
    }

    private String resolveProviderModel(String provider, String clientModel) {
        String aliasValue = properties.getUpstream().getModelAlias().get(clientModel);
        if (!StringUtils.hasText(aliasValue)) {
            return clientModel;
        }
        String[] parts = aliasValue.split(":", 2);
        if (parts.length == 2) {
            return provider.equals(parts[0]) ? parts[1] : clientModel;
        }
        return aliasValue;
    }

    private String providerMetricModel(String provider, String model) {
        return provider + ":" + model;
    }

    private String metricKey(String model, String hour) {
        return METRIC_KEY_PREFIX + model + ":" + hour;
    }

    private String latencyKey(String model, String hour) {
        return LATENCY_KEY_PREFIX + model + ":" + hour;
    }

    private String currentHour() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
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
