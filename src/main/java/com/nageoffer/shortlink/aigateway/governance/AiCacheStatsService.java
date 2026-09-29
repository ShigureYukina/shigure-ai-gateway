package com.nageoffer.shortlink.aigateway.governance;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 缓存命中统计（进程内快照）。
 * <p>
 * 定位：<b>本实例</b>的实时计数与分钟趋势，重启清零、多实例各自独立。因此它适合"看这台机器的
 * 缓存是否在起作用、刚改完配置有没有变化"，不适合当作跨实例的汇总口径。
 * <p>
 * 跨实例的汇聚口径在 Redis 里（租户维度 hash 的 {@code cacheHit}/{@code cacheMiss}/{@code cacheWrite}，
 * 字段名由 {@code AiGatewayMetricsKeys} 约定）。两者数值本来就不会相等，但<b>必须对"什么算命中"用同一个定义</b>：
 * 精确命中与语义命中都算命中。所以这里的 {@code hitRate} 分子分母都含语义命中——
 * 只把精确命中算作命中，会得到明显低于 Redis 侧 {@code cacheHit} 的命中率，两个数摆在同一个控制台上就是自相矛盾。
 */
@Component
public class AiCacheStatsService {

    private static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final LongAdder hit = new LongAdder();

    private final LongAdder miss = new LongAdder();

    private final LongAdder semanticHit = new LongAdder();

    private final LongAdder write = new LongAdder();

    private final ConcurrentHashMap<Long, BucketCounter> minuteBuckets = new ConcurrentHashMap<>();

    public void recordHit() {
        hit.increment();
        bucketForCurrentMinute().hit.increment();
    }

    public void recordMiss() {
        miss.increment();
        bucketForCurrentMinute().miss.increment();
    }

    public void recordSemanticHit() {
        semanticHit.increment();
        bucketForCurrentMinute().semanticHit.increment();
    }

    public void recordWrite() {
        write.increment();
        bucketForCurrentMinute().write.increment();
    }

    public Map<String, Object> snapshot() {
        long hitValue = hit.sum();
        long missValue = miss.sum();
        long semanticHitValue = semanticHit.sum();
        return Map.of(
                "hit", hitValue,
                "miss", missValue,
                "semanticHit", semanticHitValue,
                "write", write.sum(),
                "totalLookup", totalLookup(hitValue, semanticHitValue, missValue),
                "hitRate", hitRate(hitValue, semanticHitValue, missValue)
        );
    }

    public List<Map<String, Object>> trend(int minutes) {
        int normalized = Math.min(Math.max(minutes, 1), 24 * 60);
        long nowMinute = currentMinuteEpoch();
        List<Map<String, Object>> result = new ArrayList<>();
        for (long i = nowMinute - normalized + 1; i <= nowMinute; i++) {
            BucketCounter bucket = minuteBuckets.get(i);
            long hitValue = bucket == null ? 0L : bucket.hit.sum();
            long missValue = bucket == null ? 0L : bucket.miss.sum();
            long semanticHitValue = bucket == null ? 0L : bucket.semanticHit.sum();
            result.add(Map.of(
                    "minute", formatMinute(i),
                    "hit", hitValue,
                    "miss", missValue,
                    "semanticHit", semanticHitValue,
                    "write", bucket == null ? 0L : bucket.write.sum(),
                    "hitRate", hitRate(hitValue, semanticHitValue, missValue)
            ));
        }
        return result.stream().sorted(Comparator.comparing(each -> String.valueOf(each.get("minute")))).toList();
    }

    /**
     * 一次缓存查找的三种归宿：精确命中、语义命中、未命中。三者之和才是分母。
     */
    private long totalLookup(long hitValue, long semanticHitValue, long missValue) {
        return hitValue + semanticHitValue + missValue;
    }

    private double hitRate(long hitValue, long semanticHitValue, long missValue) {
        long totalLookup = totalLookup(hitValue, semanticHitValue, missValue);
        if (totalLookup == 0) {
            return 0D;
        }
        return (double) (hitValue + semanticHitValue) / totalLookup;
    }

    public Map<String, Object> reset() {
        hit.reset();
        miss.reset();
        semanticHit.reset();
        write.reset();
        minuteBuckets.clear();
        return snapshot();
    }

    private BucketCounter bucketForCurrentMinute() {
        long currentMinute = currentMinuteEpoch();
        cleanupOldBuckets(currentMinute);
        return minuteBuckets.computeIfAbsent(currentMinute, key -> new BucketCounter());
    }

    private long currentMinuteEpoch() {
        return Instant.now().getEpochSecond() / 60;
    }

    private String formatMinute(long minuteEpoch) {
        return MINUTE_FORMATTER.format(Instant.ofEpochSecond(minuteEpoch * 60));
    }

    private void cleanupOldBuckets(long currentMinute) {
        long threshold = currentMinute - 24 * 60;
        minuteBuckets.keySet().removeIf(each -> each < threshold);
    }

    private static class BucketCounter {

        private final LongAdder hit = new LongAdder();

        private final LongAdder miss = new LongAdder();

        private final LongAdder semanticHit = new LongAdder();

        private final LongAdder write = new LongAdder();
    }
}
