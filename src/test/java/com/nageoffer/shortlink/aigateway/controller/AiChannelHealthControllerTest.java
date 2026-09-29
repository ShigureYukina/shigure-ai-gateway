package com.nageoffer.shortlink.aigateway.controller;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderHealthEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderHealthRepository;
import com.nageoffer.shortlink.aigateway.probe.ChannelProbeService;
import com.nageoffer.shortlink.aigateway.probe.ProbeSummary;
import com.nageoffer.shortlink.aigateway.routing.ChannelHealthRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道健康管理面。
 * <p>
 * 这里用的是<b>真实的</b> {@link ChannelHealthRegistry}（配一个 mock 仓储），不是 mock 的注册表：
 * {@code usable} / {@code reason} / {@code degradation} 这几个字段都是"从快照推出来的"，
 * 把注册表 mock 掉就只剩一堆 boolean 默认值，等于什么都没测。
 * <p>
 * 重点在三种不可用的表达：yml 静态禁用、控制台人工禁用、探测判 DOWN 是<b>并列</b>的三件事，
 * 响应里必须是三个独立字段 + 一个 {@code reason}，而不是一个合并后的 status。
 */
class AiChannelHealthControllerTest {

    private static final String OPENAI = "openai";

    private AiGatewayProperties properties;

    private ChannelProbeService channelProbeService;

    private ChannelHealthRegistry channelHealthRegistry;

    private ProviderHealthRepository repository;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        properties = new AiGatewayProperties();
        properties.getUpstream().setProviderBaseUrl(new LinkedHashMap<>(Map.of(
                OPENAI, "http://127.0.0.1:11434/v1",
                "claude", "http://127.0.0.1:11434/v1",
                "qwen", "http://127.0.0.1:11434/v1")));
        properties.getUpstream().setDefaultProvider(OPENAI);
        properties.getRouting().setProviderPriority(List.of(OPENAI, "claude", "qwen"));
        properties.getProbe().getChannelEnabled().put("qwen", false);

        ProviderHealthEntity manuallyDisabled = row(OPENAI, ProviderHealthEntity.STATUS_UP, true, 0, 4);
        ProviderHealthEntity cooling = row("claude", ProviderHealthEntity.STATUS_DOWN, false, 3, 0);
        cooling.setDisabledUntil(LocalDateTime.now().plusMinutes(5));
        repository = Mockito.mock(ProviderHealthRepository.class);
        Mockito.when(repository.findAll()).thenReturn(Flux.just(manuallyDisabled, cooling));

        @SuppressWarnings("unchecked")
        ObjectProvider<ProviderHealthRepository> provider = Mockito.mock(ObjectProvider.class);
        Mockito.lenient().when(provider.getIfAvailable()).thenReturn(repository);
        channelHealthRegistry = new ChannelHealthRegistry(properties, provider);
        channelHealthRegistry.refresh().block();

        channelProbeService = Mockito.mock(ChannelProbeService.class);
        Mockito.lenient().when(channelProbeService.lastSummary()).thenReturn(null);

        webTestClient = WebTestClient.bindToController(
                        new AiChannelHealthController(channelProbeService, channelHealthRegistry, properties))
                .controllerAdvice(new AiGatewayExceptionHandler(properties))
                .build();
    }

    @Test
    void shouldDescribeEachChannelWithItsOwnReason() {
        Map<String, Object> body = health();

        Assertions.assertEquals("database", body.get("source"));
        Assertions.assertTrue(body.containsKey("lastProbe"), "字段要在，值为 null 表示还没跑过第一轮");
        Assertions.assertNull(body.get("lastProbe"));

        Map<String, Object> openai = item(body, OPENAI);
        Assertions.assertEquals(false, openai.get("usable"));
        Assertions.assertEquals(true, openai.get("manualDisabled"));
        Assertions.assertEquals(false, openai.get("down"));
        Assertions.assertEquals(false, openai.get("staticDisabled"));
        Assertions.assertEquals("控制台人工禁用", openai.get("reason"));
        Assertions.assertEquals(4, openai.get("consecutiveSuccesses"));

        Map<String, Object> claude = item(body, "claude");
        Assertions.assertEquals(false, claude.get("usable"));
        Assertions.assertEquals(true, claude.get("down"));
        Assertions.assertNotNull(claude.get("disabledUntil"));
        Assertions.assertTrue(String.valueOf(claude.get("reason")).startsWith("探测判定不可用，冷却至"));

        // qwen 是 yml 静态禁用：快照里根本没有它的行，也必须出现在清单里
        Map<String, Object> qwen = item(body, "qwen");
        Assertions.assertEquals(true, qwen.get("staticDisabled"));
        Assertions.assertEquals(false, qwen.get("usable"));
        Assertions.assertTrue(String.valueOf(qwen.get("reason")).contains("yml 静态禁用"));
        Assertions.assertEquals(0, qwen.get("consecutiveFailures"), "没探过时计数是 0 而不是 null");
        Assertions.assertNull(qwen.get("lastCheckedAt"), "没探过就要看得出没探过");
    }

    @Test
    void shouldReportAllThreeKindsOfUnavailableAsSeparateFields() {
        Map<String, Object> body = health();

        // 三个布尔必须互相独立：合并成一个 status 就表达不了
        // "yml 说禁用、运维说已启用、探测说 UP" 这种组合
        for (String provider : List.of(OPENAI, "claude", "qwen")) {
            Map<String, Object> item = item(body, provider);
            Assertions.assertNotNull(item.get("staticDisabled"), provider);
            Assertions.assertNotNull(item.get("manualDisabled"), provider);
            Assertions.assertNotNull(item.get("down"), provider);
        }
    }

    @Test
    void shouldNoteDegradationWhenNoRepositoryIsWired() {
        ChannelHealthRegistry memoryOnly = new ChannelHealthRegistry(properties);

        Map<String, Object> body = renderWith(memoryOnly, channelProbeService);

        Assertions.assertEquals("memory", body.get("source"));
        Map<String, Object> degradation = asMap(body.get("degradation"));
        Assertions.assertEquals(false, degradation.get("persistenceAvailable"));
        Assertions.assertEquals(true, degradation.get("redisAvailable"));
        Assertions.assertEquals(1, asList(degradation.get("notes")).size());
    }

    @Test
    void shouldNoteDegradationWhenRedisIsUnavailable() {
        Mockito.when(channelProbeService.redisDegraded()).thenReturn(true);

        Map<String, Object> degradation = asMap(health().get("degradation"));

        Assertions.assertEquals(true, degradation.get("persistenceAvailable"));
        Assertions.assertEquals(false, degradation.get("redisAvailable"));
        Assertions.assertTrue(String.valueOf(asList(degradation.get("notes")).get(0)).contains("Redis"));
    }

    @Test
    void shouldProbeAllChannelsOnDemandAndReturnTheRoundResult() {
        ProbeSummary summary = new ProbeSummary(
                3, 1, 1, 1, List.of("qwen: 已手动禁用"), List.of("claude"), List.of(), false, Instant.EPOCH);
        Mockito.when(channelProbeService.probeAll(true)).thenReturn(Mono.just(summary));
        // 真实实现里 lastSummary 是 probeAll 自己写进去的，mock 不会产生副作用，手工补上
        Mockito.when(channelProbeService.lastSummary()).thenReturn(summary);

        Map<String, Object> body = webTestClient.post()
                .uri("/v1/routing/channels/health/probe")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .returnResult().getResponseBody();

        Map<String, Object> lastProbe = asMap(body.get("lastProbe"));
        Assertions.assertEquals(3, lastProbe.get("probed"));
        Assertions.assertEquals(1, lastProbe.get("unhealthy"));
        Assertions.assertEquals(1, lastProbe.get("inconclusive"));
        Assertions.assertEquals(List.of("claude"), lastProbe.get("disabled"));
        Assertions.assertEquals(List.of("qwen: 已手动禁用"), lastProbe.get("skipped"));

        // 强制：运维点了按钮就该立刻有结果，不该被别的实例的锁挡回去
        Mockito.verify(channelProbeService).probeAll(true);
    }

    @Test
    void shouldReloadSnapshotWithoutProbing() {
        webTestClient.post()
                .uri("/v1/routing/channels/health/reload")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.source").isEqualTo("database");

        Mockito.verify(channelProbeService, Mockito.never()).probeAll(Mockito.anyBoolean());
    }

    @Test
    void shouldManuallyDisableChannel() {
        Mockito.when(channelProbeService.setManualDisabled(OPENAI, true)).thenReturn(Mono.just(true));

        webTestClient.post()
                .uri("/v1/routing/channels/health/" + OPENAI)
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.source").isEqualTo("database");

        Mockito.verify(channelProbeService).setManualDisabled(OPENAI, true);
    }

    @Test
    void shouldManuallyEnableChannelIncludingBooleanAsString() {
        Mockito.when(channelProbeService.setManualDisabled(OPENAI, false)).thenReturn(Mono.just(true));

        // 控制台表单提交过来的常常是字符串
        webTestClient.post()
                .uri("/v1/routing/channels/health/" + OPENAI)
                .bodyValue(Map.of("enabled", "true"))
                .exchange()
                .expectStatus().isOk();

        Mockito.verify(channelProbeService).setManualDisabled(OPENAI, false);
    }

    @Test
    void shouldRejectToggleWithoutBooleanEnabled() {
        webTestClient.post()
                .uri("/v1/routing/channels/health/" + OPENAI)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_request");

        Mockito.verify(channelProbeService, Mockito.never()).setManualDisabled(Mockito.anyString(), Mockito.anyBoolean());
    }

    @Test
    void shouldRejectToggleForProviderThatIsNotConfigured() {
        // 不在候选集里的渠道本来就不会被路由选中，给它写人工禁用只是往库里塞一条没人看的行
        webTestClient.post()
                .uri("/v1/routing/channels/health/gemini")
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.message").value(message -> Assertions.assertTrue(
                        String.valueOf(message).contains("gemini")));

        Mockito.verify(channelProbeService, Mockito.never()).setManualDisabled(Mockito.anyString(), Mockito.anyBoolean());
    }

    @Test
    void shouldFailWhenManualToggleCannotBePersisted() {
        Mockito.when(channelProbeService.setManualDisabled(OPENAI, true)).thenReturn(Mono.just(false));

        // 不能返回 200 + persisted=false 了事：状态根本没变，返回成功会让运维以为生效了
        webTestClient.post()
                .uri("/v1/routing/channels/health/" + OPENAI)
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_request");
    }

    private Map<String, Object> health() {
        return renderWith(channelHealthRegistry, channelProbeService);
    }

    private Map<String, Object> renderWith(ChannelHealthRegistry registry, ChannelProbeService probeService) {
        WebTestClient client = WebTestClient.bindToController(
                        new AiChannelHealthController(probeService, registry, properties))
                .controllerAdvice(new AiGatewayExceptionHandler(properties))
                .build();
        return client.get()
                .uri("/v1/routing/channels/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> item(Map<String, Object> body, String provider) {
        for (Map<String, Object> item : (List<Map<String, Object>>) body.get("items")) {
            if (provider.equals(item.get("provider"))) {
                return item;
            }
        }
        throw new AssertionError("响应里没有渠道 " + provider + "：" + body.get("items"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    private static ProviderHealthEntity row(String provider, String status, boolean manualDisabled,
                                            int failures, int successes) {
        ProviderHealthEntity entity = new ProviderHealthEntity();
        entity.setProvider(provider);
        entity.setStatus(status);
        entity.setManualDisabled(manualDisabled);
        entity.setConsecutiveFailures(failures);
        entity.setConsecutiveSuccesses(successes);
        entity.setLastCheckedAt(LocalDateTime.now());
        return entity;
    }
}
