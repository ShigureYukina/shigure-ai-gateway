package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayRoutingProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通道组的策略选择与配置约束。
 */
class ProviderGroupServiceTest {

    private static final String MODEL = "gpt-4o-mini";

    private AiGatewayProperties properties;

    private ProviderHealthScoreService healthScoreService;

    @BeforeEach
    void setUp() {
        properties = new AiGatewayProperties();
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");
        healthScoreService = Mockito.mock(ProviderHealthScoreService.class);
        Mockito.when(healthScoreService.getProviderScores(Mockito.anyString())).thenReturn(List.of());
    }

    @Test
    void shouldOnlySelectGroupForBoundModel() {
        ProviderGroupService service = serviceWithStrategy(AiGatewayRoutingProperties.LoadBalanceStrategy.PRIORITY);

        Assertions.assertNotNull(service.selectForModel(MODEL));
        Assertions.assertEquals("default", service.selectForModel(MODEL).groupName());
        Assertions.assertNull(service.selectForModel("unbound-model"));
    }

    @Test
    void shouldOrderByPriorityThenWeight() {
        ProviderGroupService service = serviceWithStrategy(AiGatewayRoutingProperties.LoadBalanceStrategy.PRIORITY);

        List<String> providers = service.selectForModel(MODEL).order().stream()
                .map(ProviderGroupService.GroupMember::provider)
                .toList();

        Assertions.assertEquals(List.of("openai", "claude"), providers);
    }

    @Test
    void shouldRotateBetweenMembersOnRoundRobin() {
        ProviderGroupService service = serviceWithStrategy(AiGatewayRoutingProperties.LoadBalanceStrategy.ROUND_ROBIN);

        List<String> primaries = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            primaries.add(service.selectForModel(MODEL).order().get(0).provider());
        }

        Assertions.assertEquals(List.of("openai", "claude", "openai", "claude"), primaries);
    }

    @Test
    void shouldDistributeTrafficByWeight() {
        ProviderGroupService service = serviceWithStrategy(AiGatewayRoutingProperties.LoadBalanceStrategy.WEIGHTED);

        int openaiHits = 0;
        int rounds = 2000;
        for (int i = 0; i < rounds; i++) {
            if ("openai".equals(service.selectForModel(MODEL).order().get(0).provider())) {
                openaiHits++;
            }
        }

        // 权重 openai:claude = 3:1，允许统计波动
        double ratio = openaiHits * 1.0D / rounds;
        Assertions.assertTrue(ratio > 0.68 && ratio < 0.82, "实际占比 " + ratio);
    }

    @Test
    void shouldHitBothMembersOnRandomStrategy() {
        ProviderGroupService service = serviceWithStrategy(AiGatewayRoutingProperties.LoadBalanceStrategy.RANDOM);

        boolean openaiSeen = false;
        boolean claudeSeen = false;
        for (int i = 0; i < 200; i++) {
            String primary = service.selectForModel(MODEL).order().get(0).provider();
            openaiSeen |= "openai".equals(primary);
            claudeSeen |= "claude".equals(primary);
        }

        Assertions.assertTrue(openaiSeen && claudeSeen);
    }

    @Test
    void shouldOrderByHealthScoreOnDynamicStrategy() {
        Mockito.when(healthScoreService.getProviderScores(MODEL)).thenReturn(List.of(
                healthScore("claude", 92),
                healthScore("openai", 40)));
        ProviderGroupService service = serviceWithStrategy(AiGatewayRoutingProperties.LoadBalanceStrategy.DYNAMIC);

        List<String> providers = service.selectForModel(MODEL).order().stream()
                .map(ProviderGroupService.GroupMember::provider)
                .toList();

        Assertions.assertEquals(List.of("claude", "openai"), providers);
    }

    @Test
    void shouldFallBackToPriorityOrderWhenHealthScoreMissing() {
        ProviderGroupService service = serviceWithStrategy(AiGatewayRoutingProperties.LoadBalanceStrategy.DYNAMIC);

        List<String> providers = service.selectForModel(MODEL).order().stream()
                .map(ProviderGroupService.GroupMember::provider)
                .toList();

        Assertions.assertEquals(List.of("openai", "claude"), providers);
    }

    @Test
    void shouldSkipDisabledGroupAndUnavailableMember() {
        AiGatewayRoutingProperties.ProviderGroupConfig group = groupConfig("openai", "claude");
        group.setEnabled(false);
        properties.getRouting().getProviderGroups().put("default", group);
        properties.getRouting().getModelGroups().put(MODEL, "default");
        ProviderGroupService disabled = new ProviderGroupService(properties, healthScoreService);
        Assertions.assertNull(disabled.selectForModel(MODEL));

        group.setEnabled(true);
        properties.getUpstream().getProviderBaseUrl().remove("openai");
        properties.getUpstream().getProviderBaseUrl().remove("claude");
        ProviderGroupService noUpstream = new ProviderGroupService(properties, healthScoreService);
        Assertions.assertNull(noUpstream.selectForModel(MODEL));
    }

    @Test
    void shouldRejectUnknownStrategyAndEmptyMembers() {
        ProviderGroupService service = serviceWithStrategy(AiGatewayRoutingProperties.LoadBalanceStrategy.PRIORITY);

        Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.saveGroup("default", "not-a-strategy", true, null, List.of()));

        Assertions.assertThrows(AiGatewayClientException.class,
                () -> service.saveGroup("default", "PRIORITY", true, null, List.of()));
    }

    @Test
    void shouldReportYmlSeedWhenDatabaseAbsent() {
        ProviderGroupService service = serviceWithStrategy(AiGatewayRoutingProperties.LoadBalanceStrategy.PRIORITY);

        Map<String, Object> description = service.describe();

        Assertions.assertEquals("yml", description.get("source"));
        Assertions.assertEquals(1, ((List<?>) description.get("groups")).size());
        Assertions.assertEquals("default", ((Map<?, ?>) description.get("bindings")).get(MODEL));
    }

    @Test
    void shouldExposeMemberModelOverrideInPreview() {
        AiGatewayRoutingProperties.ProviderGroupConfig group = groupConfig("openai", "claude");
        group.getMembers().get(1).setModel("claude-3-5-sonnet-latest");
        properties.getRouting().getProviderGroups().put("default", group);
        properties.getRouting().getModelGroups().put(MODEL, "default");
        ProviderGroupService service = new ProviderGroupService(properties, healthScoreService);

        Map<String, Object> preview = service.preview(MODEL);

        Assertions.assertEquals("default", preview.get("group"));
        Assertions.assertEquals("group-priority", preview.get("routeSource"));
        List<?> order = (List<?>) preview.get("order");
        Assertions.assertEquals("claude-3-5-sonnet-latest", ((Map<?, ?>) order.get(1)).get("model"));
    }

    private ProviderHealthScore healthScore(String provider, int score) {
        return ProviderHealthScore.builder()
                .provider(provider)
                .model(MODEL)
                .healthScore(score)
                .avgLatencyMillis(100L)
                .successRate(0.9D)
                .costPerCall(0.001D)
                .lastUpdated(Instant.now())
                .build();
    }

    private ProviderGroupService serviceWithStrategy(AiGatewayRoutingProperties.LoadBalanceStrategy strategy) {
        properties.getRouting().getProviderGroups().put("default", groupConfig("openai", "claude"));
        properties.getRouting().getProviderGroups().get("default").setStrategy(strategy);
        properties.getRouting().getModelGroups().put(MODEL, "default");
        return new ProviderGroupService(properties, healthScoreService);
    }

    private AiGatewayRoutingProperties.ProviderGroupConfig groupConfig(String primary, String secondary) {
        AiGatewayRoutingProperties.ProviderGroupConfig group = new AiGatewayRoutingProperties.ProviderGroupConfig();
        group.setStrategy(AiGatewayRoutingProperties.LoadBalanceStrategy.PRIORITY);
        group.setMembers(List.of(memberOf(primary, 3, 2), memberOf(secondary, 1, 1)));
        return group;
    }

    private AiGatewayRoutingProperties.GroupMemberConfig memberOf(String provider, int weight, int priority) {
        AiGatewayRoutingProperties.GroupMemberConfig member = new AiGatewayRoutingProperties.GroupMemberConfig();
        member.setProvider(provider);
        member.setWeight(weight);
        member.setPriority(priority);
        return member;
    }
}
