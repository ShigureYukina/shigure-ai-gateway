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
        long totalLookup = hitValue + missValue;
        double hitRate = totalLookup == 0 ? 0D : (double) hitValue / totalLookup;
        return Map.of(
                "hit", hitValue,
                "miss", missValue,
                "semanticHit", semanticHit.sum(),
                "write", write.sum(),
                "totalLookup", totalLookup,
                "hitRate", hitRate
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
            long totalLookup = hitValue + missValue;
            double hitRate = totalLookup == 0 ? 0D : (double) hitValue / totalLookup;
            result.add(Map.of(
                    "minute", formatMinute(i),
                    "hit", hitValue,
                    "miss", missValue,
                    "semanticHit", bucket == null ? 0L : bucket.semanticHit.sum(),
                    "write", bucket == null ? 0L : bucket.write.sum(),
                    "hitRate", hitRate
            ));
        }
        return result.stream().sorted(Comparator.comparing(each -> String.valueOf(each.get("minute")))).toList();
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
