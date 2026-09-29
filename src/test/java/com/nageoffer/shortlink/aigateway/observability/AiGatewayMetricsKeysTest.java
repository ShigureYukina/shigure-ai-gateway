package com.nageoffer.shortlink.aigateway.observability;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 键布局的唯一权威测试。
 * <p>
 * 这些断言存在的意义不是"测工具方法"，而是把<b>跨类的隐式契约</b>钉死：
 * <ul>
 *   <li>三个类不再各自拼键，键形状由本类固定；</li>
 *   <li>记录器的 Lua 脚本里的 hash 字段名与 {@link AiGatewayMetricsKeys} 常量必须逐字一致——健康分
 *       读的是同一个 hash，字段名漂移不会有编译错误，只会让健康分静默全零；</li>
 *   <li>latency zset 成员"最后一段冒号后是毫秒时间戳"的约定，对 provider 限定键同样成立。</li>
 * </ul>
 */
class AiGatewayMetricsKeysTest {

    /**
     * 抓出 Lua 脚本里所有 HINCRBY / HINCRBYFLOAT 的目标字段名。
     */
    private static final Pattern HASH_FIELD_PATTERN =
            Pattern.compile("(?:HINCRBY|HINCRBYFLOAT)', KEYS\\[\\d+\\], '([A-Za-z]+)'");

    @Test
    void shouldBuildExactKeyShapes() {
        Assertions.assertEquals("short-link:ai-gateway:metric:model:gpt-4o-mini:2026091812",
                AiGatewayMetricsKeys.modelMetricKey("gpt-4o-mini", "2026091812"));
        Assertions.assertEquals("short-link:ai-gateway:metric:latency:gpt-4o-mini:2026091812",
                AiGatewayMetricsKeys.latencyKey("gpt-4o-mini", "2026091812"));
        Assertions.assertEquals("short-link:ai-gateway:metric:tenant:tenant-a:2026091812",
                AiGatewayMetricsKeys.tenantMetricKey("tenant-a", "2026091812"));
        Assertions.assertEquals("short-link:ai-gateway:call:20260918",
                AiGatewayMetricsKeys.callKey(LocalDate.of(2026, 9, 18)));
    }

    @Test
    void shouldKeepProviderQualifiedKeysInTheSameNamespace() {
        // 健康分按 provider 分别统计：provider:model 作为 model 段复用同一个键前缀
        String metricModel = AiGatewayMetricsKeys.providerModel("claude", "claude-3-5-sonnet-latest");
        Assertions.assertEquals("claude:claude-3-5-sonnet-latest", metricModel);
        Assertions.assertEquals("short-link:ai-gateway:metric:model:claude:claude-3-5-sonnet-latest:2026091812",
                AiGatewayMetricsKeys.modelMetricKey(metricModel, "2026091812"));
        Assertions.assertEquals("short-link:ai-gateway:metric:latency:claude:claude-3-5-sonnet-latest:2026091812",
                AiGatewayMetricsKeys.latencyKey(metricModel, "2026091812"));
    }

    @Test
    void shouldNeverEmitBlankKeySegmentForMissingTenant() {
        // 之前少了这个兜底会写出 "...:metric:tenant::2026091812" 这种空段键
        Assertions.assertEquals("short-link:ai-gateway:metric:tenant:unknown:2026091812",
                AiGatewayMetricsKeys.tenantMetricKey(null, "2026091812"));
        Assertions.assertEquals("short-link:ai-gateway:metric:tenant:unknown:2026091812",
                AiGatewayMetricsKeys.tenantMetricKey("  ", "2026091812"));
        Assertions.assertEquals("unknown", AiGatewayMetricsKeys.safe(null));
        Assertions.assertEquals("tenant-a", AiGatewayMetricsKeys.safe("tenant-a"));
    }

    @Test
    void shouldFormatHourBucketAsTenDigits() {
        String hour = AiGatewayMetricsKeys.currentHour();
        Assertions.assertEquals(10, hour.length(), "yyyyMMddHH 必须是 10 位: " + hour);
        Assertions.assertTrue(hour.chars().allMatch(Character::isDigit), hour);
    }

    @Test
    void shouldKeepTtlIntentExplicit() {
        // 聚合值只需覆盖"当前小时 + 上一小时"的查询窗口；明细要活到账单按日回溯
        Assertions.assertEquals(Duration.ofDays(2), AiGatewayMetricsKeys.METRIC_TTL);
        Assertions.assertEquals(Duration.ofDays(7), AiGatewayMetricsKeys.CALL_TTL);
    }

    @Test
    void shouldRoundTripLatencyMemberForBothCallers() {
        // 记录器：以 requestId 为前缀
        String callMember = AiGatewayMetricsKeys.latencyMember("req-1", 1_758_100_000_000L);
        Assertions.assertEquals("req-1:1758100000000", callMember);
        Assertions.assertEquals(1_758_100_000_000L, AiGatewayMetricsKeys.latencyMemberTimestamp(callMember));

        // 健康分：以 provider:model 为前缀，多一层冒号，解析取最后一段
        String providerMember = AiGatewayMetricsKeys.latencyMember(
                AiGatewayMetricsKeys.providerModel("claude", "claude-3-5-sonnet-latest"), 1_758_100_000_001L);
        Assertions.assertEquals(1_758_100_000_001L, AiGatewayMetricsKeys.latencyMemberTimestamp(providerMember));
    }

    @Test
    void shouldReturnNullForUnparsableLatencyMember() {
        Assertions.assertNull(AiGatewayMetricsKeys.latencyMemberTimestamp(null));
        Assertions.assertNull(AiGatewayMetricsKeys.latencyMemberTimestamp("  "));
        Assertions.assertNull(AiGatewayMetricsKeys.latencyMemberTimestamp("no-separator"));
        Assertions.assertNull(AiGatewayMetricsKeys.latencyMemberTimestamp("req-1:"));
        Assertions.assertNull(AiGatewayMetricsKeys.latencyMemberTimestamp("req-1:not-a-number"));
    }

    @Test
    void shouldMapCacheEventTypeToAggregationField() {
        Assertions.assertEquals(AiGatewayMetricsKeys.FIELD_CACHE_HIT, AiGatewayMetricsKeys.cacheEventField("hit"));
        Assertions.assertEquals(AiGatewayMetricsKeys.FIELD_CACHE_MISS, AiGatewayMetricsKeys.cacheEventField("miss"));
        Assertions.assertEquals(AiGatewayMetricsKeys.FIELD_CACHE_WRITE, AiGatewayMetricsKeys.cacheEventField("write"));
        Assertions.assertNull(AiGatewayMetricsKeys.cacheEventField("evict"));
        Assertions.assertNull(AiGatewayMetricsKeys.cacheEventField(null));
        Assertions.assertNull(AiGatewayMetricsKeys.cacheEventField("  "));
    }

    @Test
    void shouldKeepLuaScriptHashFieldsInSyncWithConstants() {
        Set<String> scriptFields = new LinkedHashSet<>();
        Matcher matcher = HASH_FIELD_PATTERN.matcher(AiGatewayMetricsRecorder.callRecordScriptText());
        while (matcher.find()) {
            scriptFields.add(matcher.group(1));
        }

        // 逐字对齐：改常量不改脚本、或改脚本不改常量，两个方向都会红
        Assertions.assertEquals(
                Set.of(
                        AiGatewayMetricsKeys.FIELD_CALLS,
                        AiGatewayMetricsKeys.FIELD_SUCCESS,
                        AiGatewayMetricsKeys.FIELD_COST,
                        AiGatewayMetricsKeys.FIELD_TOKEN_IN,
                        AiGatewayMetricsKeys.FIELD_TOKEN_OUT,
                        AiGatewayMetricsKeys.FIELD_CACHE_HIT),
                scriptFields);
    }

    @Test
    void shouldBuildAllMetricKeysFromASinglePrefix() {
        // 任何键都必须挂在同一个根前缀下，避免出现只在运维 grep 时才发现的旁支命名空间
        String hour = "2026091812";
        List<String> keys = List.of(
                AiGatewayMetricsKeys.modelMetricKey("m", hour),
                AiGatewayMetricsKeys.latencyKey("m", hour),
                AiGatewayMetricsKeys.tenantMetricKey("t", hour),
                AiGatewayMetricsKeys.callKey(LocalDate.of(2026, 9, 18)),
                AiGatewayMetricsKeys.modelMetricKey(AiGatewayMetricsKeys.providerModel("p", "m"), hour));
        for (String key : keys) {
            Assertions.assertTrue(key.startsWith(AiGatewayMetricsKeys.PREFIX), key);
        }
    }
}
