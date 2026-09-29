package com.nageoffer.shortlink.aigateway.routing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

class ModelAliasResolverTest {

    @Test
    void shouldParseBothAliasForms() {
        ModelAliasResolver.Alias pinned = ModelAliasResolver.parse("claude:claude-3-5-sonnet-latest");
        Assertions.assertEquals("claude", pinned.provider());
        Assertions.assertEquals("claude-3-5-sonnet-latest", pinned.model());
        Assertions.assertTrue(pinned.pinsProvider());

        ModelAliasResolver.Alias nameOnly = ModelAliasResolver.parse("gpt-4o-mini");
        Assertions.assertNull(nameOnly.provider());
        Assertions.assertEquals("gpt-4o-mini", nameOnly.model());
        Assertions.assertFalse(nameOnly.pinsProvider());
    }

    @Test
    void shouldTreatBlankAliasAsMissing() {
        Assertions.assertTrue(ModelAliasResolver.parse(null).missing());
        Assertions.assertTrue(ModelAliasResolver.parse("").missing());
        Assertions.assertTrue(ModelAliasResolver.parse("   ").missing());
    }

    @Test
    void shouldOnlySplitOnFirstColonSoModelNamesContainingColonSurvive() {
        // 本地推理常用 llama3:8b 这种带冒号的模型名，按最后一个冒号拆会把它截断
        ModelAliasResolver.Alias alias = ModelAliasResolver.parse("ollama:llama3:8b");
        Assertions.assertEquals("ollama", alias.provider());
        Assertions.assertEquals("llama3:8b", alias.model());
    }

    @Test
    void shouldLetAliasOverrideProviderWhenResolvingForRouting() {
        ModelAliasResolver.Alias resolved = ModelAliasResolver.resolve(
                Map.of("compat", "claude:claude-3-5-sonnet-latest"), "compat", "openai");

        Assertions.assertEquals("claude", resolved.provider());
        Assertions.assertEquals("claude-3-5-sonnet-latest", resolved.model());
    }

    @Test
    void shouldKeepFallbackProviderWhenAliasOnlyRenamesModel() {
        ModelAliasResolver.Alias resolved = ModelAliasResolver.resolve(Map.of("mini", "gpt-4o-mini"), "mini", "openai");

        Assertions.assertEquals("openai", resolved.provider());
        Assertions.assertEquals("gpt-4o-mini", resolved.model());
    }

    @Test
    void shouldKeepClientModelWhenAliasMissing() {
        ModelAliasResolver.Alias resolved = ModelAliasResolver.resolve(Map.of(), "gpt-4o", "openai");

        Assertions.assertEquals("openai", resolved.provider());
        Assertions.assertEquals("gpt-4o", resolved.model());
    }

    @Test
    void shouldNotRenameModelOnProviderTheAliasDoesNotPin() {
        Map<String, String> aliases = Map.of("compat", "claude:claude-3-5-sonnet-latest");

        // 别名钉的是 claude，站在 openai 上就不能拿这个模型名去查指标
        Assertions.assertEquals("compat", ModelAliasResolver.modelNameOn(aliases, "compat", "openai"));
        Assertions.assertEquals("claude-3-5-sonnet-latest",
                ModelAliasResolver.modelNameOn(aliases, "compat", "claude"));
    }

    @Test
    void shouldRenameModelOnAnyProviderWhenAliasHasNoProvider() {
        Map<String, String> aliases = Map.of("mini", "gpt-4o-mini");

        Assertions.assertEquals("gpt-4o-mini", ModelAliasResolver.modelNameOn(aliases, "mini", "openai"));
        Assertions.assertEquals("gpt-4o-mini", ModelAliasResolver.modelNameOn(aliases, "mini", "claude"));
        Assertions.assertEquals("gpt-4o", ModelAliasResolver.modelNameOn(aliases, "gpt-4o", "openai"));
    }
}
