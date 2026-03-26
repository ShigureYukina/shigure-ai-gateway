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
        Assertions.assertEquals(3L, snapshot.get("totalLookup"));

        double hitRate = (double) snapshot.get("hitRate");
        Assertions.assertTrue(hitRate > 0.66D && hitRate < 0.67D);

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
}
