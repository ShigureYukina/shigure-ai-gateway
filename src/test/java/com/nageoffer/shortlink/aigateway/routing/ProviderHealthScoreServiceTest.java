package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderHealthScoreServiceTest {

    private StringRedisTemplate stringRedisTemplate;
    private HashOperations<String, Object, Object> hashOps;
    private ZSetOperations<String, String> zSetOps;
    private AiGatewayProperties properties;
    private ProviderHealthScoreService service;

    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        zSetOps = mock(ZSetOperations.class);
        properties = new AiGatewayProperties();
        properties.getRouting().setProviderPriority(List.of("openai", "claude"));
        properties.getUpstream().setDefaultProvider("openai");
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");
        properties.getRouting().setRoutingStrategy(AiGatewayProperties.RoutingStrategy.DYNAMIC);

        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);

        service = new ProviderHealthScoreService(stringRedisTemplate, properties);
    }

    @Test
    void shouldReturnEmptyWhenCallCountBelowMinimum() {
        when(hashOps.get(anyString(), anyString())).thenReturn(null);

        List<ProviderHealthScore> scores = service.getProviderScores("gpt-4o");

        Assertions.assertTrue(scores.isEmpty());
    }

    @Test
    void shouldReturnScoresWhenCallCountSufficient() {
        when(hashOps.get(anyString(), eq("calls"))).thenReturn(10L);
        when(hashOps.get(anyString(), eq("success"))).thenReturn(9L);
        when(hashOps.get(anyString(), eq("cost"))).thenReturn(0.05);
        when(zSetOps.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(mockEmptyTuples());

        List<ProviderHealthScore> scores = service.getProviderScores("gpt-4o");

        Assertions.assertFalse(scores.isEmpty());
        ProviderHealthScore top = scores.get(0);
        Assertions.assertNotNull(top.getProvider());
        Assertions.assertNotNull(top.getHealthScore());
        Assertions.assertNotNull(top.getSuccessRate());
    }

    @Test
    void shouldSortByHealthScoreDescending() {
        when(hashOps.get(anyString(), eq("calls"))).thenReturn(10L);
        when(hashOps.get(anyString(), eq("success"))).thenReturn(8L);
        when(hashOps.get(anyString(), eq("cost"))).thenReturn(0.02);
        when(zSetOps.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(mockEmptyTuples());

        List<ProviderHealthScore> scores = service.getProviderScores("gpt-4o");

        for (int i = 0; i < scores.size() - 1; i++) {
            Assertions.assertTrue(scores.get(i).getHealthScore() >= scores.get(i + 1).getHealthScore());
        }
    }

    @Test
    void shouldReturnBestProviderForDynamicStrategy() {
        when(hashOps.get(anyString(), eq("calls"))).thenReturn(10L);
        when(hashOps.get(anyString(), eq("success"))).thenReturn(9L);
        when(hashOps.get(anyString(), eq("cost"))).thenReturn(0.03);
        when(zSetOps.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(mockEmptyTuples());

        String best = service.getBestProvider("gpt-4o");

        Assertions.assertNotNull(best);
    }

    @Test
    void shouldReturnNullWhenNoScoresAvailable() {
        when(hashOps.get(anyString(), anyString())).thenReturn(null);

        String best = service.getBestProvider("gpt-4o");

        Assertions.assertNull(best);
    }

    @Test
    void shouldRecordProviderMetrics() {
        doReturn(1L).when(hashOps).increment(anyString(), anyString(), anyLong());
        when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(true);

        service.recordProviderMetrics("openai", "gpt-4o", 500, true, 0.002);

        verify(hashOps).increment(anyString(), eq("calls"), anyLong());
        verify(hashOps).increment(anyString(), eq("success"), anyLong());
        verify(zSetOps).add(anyString(), anyString(), eq(500.0));
    }

    @Test
    void shouldNotRecordMetricsWhenProviderBlank() {
        service.recordProviderMetrics("", "gpt-4o", 500, true, 0.002);
        Mockito.verifyNoInteractions(hashOps);
    }

    @Test
    void shouldNotRecordMetricsWhenModelBlank() {
        service.recordProviderMetrics("openai", "", 500, true, 0.002);
        Mockito.verifyNoInteractions(hashOps);
    }

    @Test
    void shouldRecordFailedCallWithoutSuccessIncrement() {
        when(hashOps.increment(anyString(), anyString(), anyLong())).thenReturn(1L);
        when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(true);

        service.recordProviderMetrics("openai", "gpt-4o", 1000, false, 0.005);

        verify(hashOps).increment(anyString(), eq("calls"), anyLong());
        Mockito.verify(hashOps, Mockito.never()).increment(anyString(), eq("success"), anyLong());
    }

    @Test
    void shouldComputeHealthScoreWithCorrectFormula() {
        when(hashOps.get(anyString(), eq("calls"))).thenReturn(100L);
        when(hashOps.get(anyString(), eq("success"))).thenReturn(100L);
        when(hashOps.get(anyString(), eq("cost"))).thenReturn(0.01);
        when(zSetOps.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(mockEmptyTuples());

        List<ProviderHealthScore> scores = service.getProviderScores("gpt-4o");

        Assertions.assertFalse(scores.isEmpty());
        ProviderHealthScore score = scores.get(0);
        // 100% success * 40 + (1 - 0) * 30 + (1 - 0) * 30 = 100
        Assertions.assertEquals(100, score.getHealthScore());
    }

    @Test
    void shouldClampHealthScoreBetween0And100() {
        when(hashOps.get(anyString(), eq("calls"))).thenReturn(10L);
        when(hashOps.get(anyString(), eq("success"))).thenReturn(0L);
        when(hashOps.get(anyString(), eq("cost"))).thenReturn(0.09);
        when(zSetOps.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(mockEmptyTuples());

        List<ProviderHealthScore> scores = service.getProviderScores("gpt-4o");

        Assertions.assertFalse(scores.isEmpty());
        ProviderHealthScore score = scores.get(0);
        Assertions.assertTrue(score.getHealthScore() >= 0);
        Assertions.assertTrue(score.getHealthScore() <= 100);
    }

    @Test
    void shouldResolveCandidateProvidersFromConfig() {
        when(hashOps.get(anyString(), anyString())).thenReturn(null);

        List<ProviderHealthScore> scores = service.getProviderScores("gpt-4o");

        Assertions.assertTrue(scores.isEmpty());
    }

    private Set<ZSetOperations.TypedTuple<String>> mockEmptyTuples() {
        return new LinkedHashSet<>();
    }
}
