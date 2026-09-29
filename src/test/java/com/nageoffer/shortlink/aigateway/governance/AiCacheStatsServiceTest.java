package com.nageoffer.shortlink.aigateway.governance;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class AiCacheStatsServiceTest {

    @Test
    void shouldRecordAndSnapshotAndReset() {
        AiCacheStatsService service = new AiCacheStatsService();

        service.recordHit();
        service.recordHit();
        service.recordMiss();
        service.recordSemanticHit();
        service.recordWrite();

        Map<String, Object> snapshot = service.snapshot();
        Assertions.assertEquals(2L, snapshot.get("hit"));
        Assertions.assertEquals(1L, snapshot.get("miss"));
        Assertions.assertEquals(1L, snapshot.get("semanticHit"));
        Assertions.assertEquals(1L, snapshot.get("write"));
        // 分母 = 精确命中 + 语义命中 + 未命中：每次缓存查找必然落在这三种归宿之一
        Assertions.assertEquals(4L, snapshot.get("totalLookup"));

        // 分子含语义命中。只把精确命中算作命中，会得到 0.5 这种明显低于 Redis 侧 cacheHit 的命中率，
        // 两个数摆在同一个控制台上就是自相矛盾
        double hitRate = (double) snapshot.get("hitRate");
        Assertions.assertEquals(0.75D, hitRate, 1e-9);

        Map<String, Object> reset = service.reset();
        Assertions.assertEquals(0L, reset.get("hit"));
        Assertions.assertEquals(0L, reset.get("miss"));
        Assertions.assertEquals(0L, reset.get("semanticHit"));
        Assertions.assertEquals(0L, reset.get("write"));
    }

    @Test
    void shouldReturnTrendWithNormalizedWindow() {
        AiCacheStatsService service = new AiCacheStatsService();

        service.recordHit();
        service.recordMiss();

        List<Map<String, Object>> trendMin = service.trend(0);
        Assertions.assertEquals(1, trendMin.size());

        List<Map<String, Object>> trendMax = service.trend(999999);
        Assertions.assertEquals(24 * 60, trendMax.size());

        Map<String, Object> last = trendMax.get(trendMax.size() - 1);
        Assertions.assertTrue(last.containsKey("minute"));
        Assertions.assertTrue(last.containsKey("hitRate"));
    }

    @Test
    void shouldCountSemanticHitAsHitInTrend() {
        AiCacheStatsService service = new AiCacheStatsService();

        service.recordSemanticHit();
        service.recordMiss();

        List<Map<String, Object>> trend = service.trend(1);
        Map<String, Object> current = trend.get(trend.size() - 1);

        Assertions.assertEquals(0L, current.get("hit"));
        Assertions.assertEquals(1L, current.get("semanticHit"));
        Assertions.assertEquals(0.5D, (double) current.get("hitRate"), 1e-9);
    }
}
