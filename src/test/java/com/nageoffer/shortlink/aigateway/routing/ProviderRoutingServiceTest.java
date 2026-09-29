package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayRoutingProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;

class ProviderRoutingServiceTest {

    @Test
    void shouldResolveAliasProviderAndModel() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().getDefaultProvider();
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");
        properties.getUpstream().getModelAlias().put("claude-compat", "claude:claude-3-5-sonnet-latest");

        ProviderRoutingService service = new ProviderRoutingService(properties, Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));
        AiRoutingResult result = service.resolve("claude-compat", new HttpHeaders());

        Assertions.assertEquals("claude", result.getProvider());
        Assertions.assertEquals("claude-3-5-sonnet-latest", result.getProviderModel());
        Assertions.assertTrue(result.getUpstreamUri().contains("/v1/chat/completions"));
    }

    @Test
    void shouldReturnFallbackCandidatesWhenFallbackEnabled() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("openai");
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");
        properties.getRouting().setFallbackEnabled(true);
        properties.getRouting().setProviderPriority(java.util.List.of("openai", "claude"));

        ProviderRoutingService service = new ProviderRoutingService(properties, Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));
        AiRoutingResult result = service.resolve("gpt-4o-mini", new HttpHeaders());

        Assertions.assertEquals("openai", result.getProvider());
        Assertions.assertNotNull(result.getFallbackCandidates());
        Assertions.assertEquals(1, result.getFallbackCandidates().size());
        Assertions.assertEquals("claude", result.getFallbackCandidates().get(0).getProvider());
        Assertions.assertTrue(result.getFallbackCandidates().get(0).getUpstreamUri().contains("/v1/chat/completions"));
    }

    @Test
    void shouldThrowWhenProviderBaseUrlMissing() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("openai");

        ProviderRoutingService service = new ProviderRoutingService(properties, Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));

        Assertions.assertThrows(
                com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException.class,
                () -> service.resolve("gpt-4o-mini", new HttpHeaders())
        );
    }

    @Test
    void shouldPreferHeaderProviderWhenPresent() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("openai");
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");

        ProviderRoutingService service = new ProviderRoutingService(properties, Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-AI-Provider", "claude");
        AiRoutingResult result = service.resolve("gpt-4o-mini", headers);

        Assertions.assertEquals("claude", result.getProvider());
        Assertions.assertEquals("header", result.getRouteSource());
        Assertions.assertTrue(result.getUpstreamUri().contains("api.anthropic.com"));
    }

    @Test
    void shouldApplyModelAliasWithoutChangingProvider() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("openai");
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com/");
        properties.getUpstream().getModelAlias().put("mini", "gpt-4o-mini");

        ProviderRoutingService service = new ProviderRoutingService(properties, Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));
        AiRoutingResult result = service.resolve("mini", new HttpHeaders());

        Assertions.assertEquals("openai", result.getProvider());
        Assertions.assertEquals("gpt-4o-mini", result.getProviderModel());
        Assertions.assertEquals("https://api.openai.com/v1/chat/completions", result.getUpstreamUri());
    }

    @Test
    void shouldRouteToAbProviderWhenAbEnabledAndPercentageMatches() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("openai");
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");
        properties.getRouting().setAbEnabled(true);
        properties.getRouting().setAbProvider("claude");
        properties.getRouting().setAbPercentage(100);

        ProviderRoutingService service = new ProviderRoutingService(properties, Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));
        AiRoutingResult result = service.resolve("gpt-4o-mini", new HttpHeaders());

        Assertions.assertEquals("claude", result.getProvider());
        Assertions.assertEquals(Boolean.TRUE, result.getAbHit());
        Assertions.assertEquals("ab", result.getRouteSource());
    }

    @Test
    void shouldNormalizeRoutingConfigUpdateAndClampAbPercentage() {
        AiGatewayProperties properties = new AiGatewayProperties();
        ProviderRoutingService service = new ProviderRoutingService(properties, Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));

        Map<String, Object> result = service.updateRoutingConfig(Map.of(
                "fallbackEnabled", true,
                "providerPriority", List.of("openai", "claude", "openai", " "),
                "abEnabled", true,
                "abProvider", " claude ",
                "abPercentage", 999,
                "providerBaseUrl", Map.of("openai", " https://api.openai.com "),
                "modelAlias", Map.of("mini", "gpt-4o-mini")
        ));

        Assertions.assertEquals(true, result.get("fallbackEnabled"));
        Assertions.assertEquals(true, result.get("abEnabled"));
        Assertions.assertEquals("claude", result.get("abProvider"));
        Assertions.assertEquals(100, result.get("abPercentage"));
        Assertions.assertEquals(List.of("openai", "claude"), result.get("providerPriority"));
        Assertions.assertEquals("https://api.openai.com", ((Map<?, ?>) result.get("providerBaseUrl")).get("openai"));
    }

    @Test
    void shouldDelegateProviderOutcomeToHealthScoreService() {
        AiGatewayProperties properties = new AiGatewayProperties();
        ProviderHealthScoreService healthScoreService = Mockito.mock(ProviderHealthScoreService.class);
        ProviderRoutingService service = new ProviderRoutingService(properties, healthScoreService,
                new ProviderGroupService(properties, healthScoreService));

        service.recordProviderOutcome("openai", "gpt-4o-mini", 320L, true, 120L, 80L);

        // 上报走异步入口：动态路由的排名数据由此产生，写失败不能影响用户请求
        Mockito.verify(healthScoreService).recordProviderMetricsAsync("openai", "gpt-4o-mini", 320L, true, 120L, 80L);
    }

    @Test
    void shouldRouteThroughGroupWithGroupScopedFallbacks() {
        AiGatewayProperties properties = groupProperties(AiGatewayRoutingProperties.LoadBalanceStrategy.PRIORITY);
        ProviderRoutingService service = new ProviderRoutingService(properties,
                Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));

        AiRoutingResult result = service.resolve("gpt-4o-mini", new HttpHeaders());

        Assertions.assertEquals("openai", result.getProvider());
        Assertions.assertEquals("gpt-4o-mini", result.getProviderModel());
        Assertions.assertEquals("group-priority", result.getRouteSource());
        // 组内其余成员构成回退链，且各自带自己的模型名；不受全局 fallback-enabled=false 影响
        Assertions.assertEquals(1, result.getFallbackCandidates().size());
        Assertions.assertEquals("claude", result.getFallbackCandidates().get(0).getProvider());
        Assertions.assertEquals("claude-3-5-sonnet-latest", result.getFallbackCandidates().get(0).getProviderModel());
        Assertions.assertTrue(result.getFallbackCandidates().get(0).getUpstreamUri().contains("/v1/chat/completions"));
    }

    @Test
    void shouldLetExplicitHeaderOverrideGroupRouting() {
        AiGatewayProperties properties = groupProperties(AiGatewayRoutingProperties.LoadBalanceStrategy.PRIORITY);
        ProviderRoutingService service = new ProviderRoutingService(properties,
                Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-AI-Provider", "claude");

        AiRoutingResult result = service.resolve("gpt-4o-mini", headers);

        Assertions.assertEquals("claude", result.getProvider());
        Assertions.assertEquals("header", result.getRouteSource());
    }

    @Test
    void shouldUseDefaultRoutingForUnboundModel() {
        AiGatewayProperties properties = groupProperties(AiGatewayRoutingProperties.LoadBalanceStrategy.PRIORITY);
        ProviderRoutingService service = new ProviderRoutingService(properties,
                Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));

        AiRoutingResult result = service.resolve("claude-3-5-sonnet-compatible", new HttpHeaders());

        Assertions.assertEquals("default", result.getRouteSource());
        Assertions.assertNotNull(result.getProvider());
    }

    @Test
    void shouldKeepGroupAsProviderAuthorityWhenAliasPinsAnotherProvider() {
        AiGatewayProperties properties = groupProperties(AiGatewayRoutingProperties.LoadBalanceStrategy.PRIORITY);
        // 同一个模型既绑了组、又配了钉住 provider 的别名
        properties.getUpstream().getModelAlias().put("gpt-4o-mini", "claude:claude-3-5-haiku-latest");
        ProviderRoutingService service = new ProviderRoutingService(properties,
                Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));

        AiRoutingResult result = service.resolve("gpt-4o-mini", new HttpHeaders());

        // 组是运维显式绑定的通道选择，别名的 provider 部分必须让位，否则"这个模型的通道已经钉死了"就失效了
        Assertions.assertEquals("openai", result.getProvider());
        Assertions.assertEquals("claude-3-5-haiku-latest", result.getProviderModel());

        // 对照组：同一个别名作用在没绑组的模型上时，provider 覆盖照常生效
        properties.getUpstream().getModelAlias().put("free-alias", "claude:claude-3-5-haiku-latest");
        AiRoutingResult unbound = service.resolve("free-alias", new HttpHeaders());
        Assertions.assertEquals("claude", unbound.getProvider());
        Assertions.assertEquals("claude-3-5-haiku-latest", unbound.getProviderModel());
    }

    @Test
    void shouldNotDuplicateVersionSegmentWhenBaseUrlAlreadyHasIt() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("openai");
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com/v1");
        ProviderRoutingService service = new ProviderRoutingService(properties,
                Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));

        AiRoutingResult result = service.resolve("gpt-4o-mini", new HttpHeaders());

        Assertions.assertEquals("https://api.openai.com/v1/chat/completions", result.getUpstreamUri());
    }

    @Test
    void shouldRespectPerProviderChatPathWithoutDuplicatingVersionSegment() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("claude");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com/v1");
        properties.getUpstream().getProviderChatPath().put("claude", "v1/messages");
        ProviderRoutingService service = new ProviderRoutingService(properties,
                Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));

        AiRoutingResult result = service.resolve("claude-3-5-sonnet-latest", new HttpHeaders());

        Assertions.assertEquals("https://api.anthropic.com/v1/messages", result.getUpstreamUri());
    }

    @Test
    void shouldRejectPrivateProviderBaseUrlOnWriteAndKeepPreviousValue() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        // prod 姿态：不放行私网。169.254.169.254 是字面量地址，判定不需要 DNS
        properties.getSecurity().getSsrf().setAllowPrivateAddresses(false);
        ProviderRoutingService service = new ProviderRoutingService(properties,
                Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));

        com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException ex = Assertions.assertThrows(
                com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException.class,
                () -> service.updateRoutingConfig(Map.of("providerBaseUrl",
                        Map.of("openai", "http://169.254.169.254/v1"))));

        Assertions.assertEquals(com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode.BAD_REQUEST,
                ex.getErrorCode());
        Assertions.assertTrue(ex.getMessage().contains("openai"), ex.getMessage());
        // 整笔拒绝：不能"跳过非法项继续写"，那样调用方会以为改成功了
        Assertions.assertEquals("https://api.openai.com",
                properties.getUpstream().getProviderBaseUrl().get("openai"));
    }

    @Test
    void shouldAcceptPrivateProviderBaseUrlWhenPrivateAddressesAreAllowed() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getSecurity().getSsrf().setAllowPrivateAddresses(true);

        ProviderRoutingService service = new ProviderRoutingService(properties,
                Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));
        service.updateRoutingConfig(Map.of("providerBaseUrl", Map.of("ollama", "http://127.0.0.1:11434")));

        Assertions.assertEquals("http://127.0.0.1:11434",
                properties.getUpstream().getProviderBaseUrl().get("ollama"));
    }

    @Test
    void shouldFailFastWhenChatPathIsMalformed() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("openai");
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        // 非法字符会让 URI.create 抛 IllegalArgumentException ——
        // 热路径必须把它翻译成"渠道配置错"（PROVIDER_NOT_CONFIGURED），而不是漏成 500
        properties.getUpstream().getProviderChatPath().put("openai", "a b");

        ProviderRoutingService service = new ProviderRoutingService(properties,
                Mockito.mock(ProviderHealthScoreService.class),
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class)));

        com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException ex = Assertions.assertThrows(
                com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException.class,
                () -> service.resolve("gpt-4o-mini", new HttpHeaders()));
        Assertions.assertEquals(com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode.PROVIDER_NOT_CONFIGURED,
                ex.getErrorCode());
    }

    @Test
    void shouldRejectExplicitlyNamedChannelWhenDisabled() {
        // 调用方点名某个渠道通常是为了对比测试或定向排查，悄悄换一个会让结论完全错误
        ProviderRoutingService service = service(baseProperties(), provider -> !"claude".equals(provider));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-AI-Provider", "claude");

        com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException ex = Assertions.assertThrows(
                com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException.class,
                () -> service.resolve("gpt-4o-mini", headers));

        Assertions.assertEquals(com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode.PROVIDER_DISABLED,
                ex.getErrorCode());
        Assertions.assertTrue(ex.getMessage().contains("claude"), ex.getMessage());
    }

    @Test
    void shouldFallBackToDisabledChannelWhenEverythingIsDisabled() {
        // 保底放行：探测误判或运维把渠道全禁了，都不该让网关对客户端返回 503。
        // 打一个"可能不健康"的通道，也比彻底不可用好。
        ProviderRoutingService service = service(baseProperties(), provider -> false);

        AiRoutingResult result = service.resolve("gpt-4o-mini", new HttpHeaders());

        Assertions.assertEquals("openai", result.getProvider());
    }

    @Test
    void shouldSkipDisabledChannelsInFallbackChain() {
        AiGatewayProperties properties = baseProperties();
        properties.getRouting().setFallbackEnabled(true);
        properties.getRouting().setProviderPriority(List.of("openai", "claude"));
        ProviderRoutingService service = service(properties, provider -> !"claude".equals(provider));

        AiRoutingResult result = service.resolve("gpt-4o-mini", new HttpHeaders());

        // 已知坏掉的渠道留在降级链里，只会让每次请求都先撞一次它
        Assertions.assertEquals("openai", result.getProvider());
        Assertions.assertTrue(result.getFallbackCandidates().isEmpty());
    }

    @Test
    void shouldFallBackToNormalRoutingWhenAbChannelIsDisabled() {
        AiGatewayProperties properties = baseProperties();
        properties.getRouting().setAbEnabled(true);
        properties.getRouting().setAbPercentage(100);
        properties.getRouting().setAbProvider("claude");
        ProviderRoutingService service = service(properties, provider -> !"claude".equals(provider));

        AiRoutingResult result = service.resolve("gpt-4o-mini", new HttpHeaders());

        // 实验组挂了不该让对照组也拿不到响应：退回常规路由，而不是报错
        Assertions.assertEquals("openai", result.getProvider());
        Assertions.assertNotEquals("ab", result.getRouteSource());
    }

    @Test
    void shouldSkipDisabledMembersInsideAProviderGroup() {
        AiGatewayProperties properties = groupProperties(AiGatewayRoutingProperties.LoadBalanceStrategy.PRIORITY);
        ProviderRoutingService service = service(properties, provider -> !"claude".equals(provider));

        AiRoutingResult result = service.resolve("gpt-4o-mini", new HttpHeaders());

        // 组是"这些通道互为备份"的声明，成员被禁时应当由组内其余成员顶上，
        // 而不是把请求仍丢给那个已知坏掉的成员
        Assertions.assertNotEquals("claude", result.getProvider());
        Assertions.assertTrue(result.getFallbackCandidates().stream()
                .noneMatch(candidate -> "claude".equals(candidate.getProvider())));
    }

    private AiGatewayProperties baseProperties() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("openai");
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");
        return properties;
    }

    private ProviderRoutingService service(AiGatewayProperties properties, ChannelHealthView view) {
        ProviderHealthScoreService scoreService = Mockito.mock(ProviderHealthScoreService.class);
        return new ProviderRoutingService(properties, scoreService,
                new ProviderGroupService(properties, Mockito.mock(ProviderHealthScoreService.class),
                        null, null, null, view),
                view);
    }

    private AiGatewayProperties groupProperties(AiGatewayRoutingProperties.LoadBalanceStrategy strategy) {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getUpstream().setDefaultProvider("openai");
        properties.getUpstream().getProviderBaseUrl().put("openai", "https://api.openai.com");
        properties.getUpstream().getProviderBaseUrl().put("claude", "https://api.anthropic.com");
        properties.getUpstream().getModelAlias().put("claude-3-5-sonnet-compatible", "claude:claude-3-5-sonnet-latest");

        AiGatewayRoutingProperties.ProviderGroupConfig group = new AiGatewayRoutingProperties.ProviderGroupConfig();
        group.setStrategy(strategy);
        AiGatewayRoutingProperties.GroupMemberConfig primary = new AiGatewayRoutingProperties.GroupMemberConfig();
        primary.setProvider("openai");
        primary.setWeight(3);
        primary.setPriority(2);
        AiGatewayRoutingProperties.GroupMemberConfig secondary = new AiGatewayRoutingProperties.GroupMemberConfig();
        secondary.setProvider("claude");
        secondary.setModel("claude-3-5-sonnet-latest");
        secondary.setWeight(1);
        secondary.setPriority(1);
        group.setMembers(List.of(primary, secondary));

        properties.getRouting().getProviderGroups().put("default", group);
        properties.getRouting().getModelGroups().put("gpt-4o-mini", "default");
        return properties;
    }
}
