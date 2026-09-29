package com.nageoffer.shortlink.aigateway.routing;

import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 模型别名的唯一解析入口。
 * <p>
 * 别名支持两种写法：
 * <ol>
 *   <li>{@code provider:model} —— 同时钉住通道与模型名；</li>
 *   <li>{@code model} —— 只改模型名，通道沿用调用方给定的。</li>
 * </ol>
 * 拆分逻辑原先在 {@link ProviderRoutingService}、{@code ProviderHealthScoreService}、
 * {@code AiModelsController} 各写了一遍 {@code split(":")}，其中健康分那处还多了一层"通道对不上就不改名"
 * 的判断——三处同名不同义，改一处必然漏一处。这里按"问什么问题"给出三个具名操作，
 * 差异是刻意保留的（它们问的确实不是同一个问题），但 {@code split} 只在这一处。
 */
public final class ModelAliasResolver {

    private ModelAliasResolver() {
    }

    /**
     * 别名拆解结果。
     *
     * @param provider 别名里显式声明的通道；只有 {@code model} 写法或未命中时为 {@code null}
     * @param model    目标模型名；未命中别名时为 {@code null}
     */
    public record Alias(String provider, String model) {

        public boolean pinsProvider() {
            return provider != null;
        }

        public boolean missing() {
            return model == null;
        }
    }

    /**
     * 拆一个别名值。容忍 {@code null} 与空串。
     */
    public static Alias parse(String aliasValue) {
        if (!StringUtils.hasText(aliasValue)) {
            return new Alias(null, null);
        }
        String[] parts = aliasValue.split(":", 2);
        if (parts.length == 2) {
            return new Alias(parts[0], parts[1]);
        }
        return new Alias(null, aliasValue);
    }

    /**
     * 路由用：别名可以覆盖通道；未命中别名时返回 {@code fallbackProvider} + 原模型名。
     */
    public static Alias resolve(Map<String, String> aliases, String clientModel, String fallbackProvider) {
        Alias alias = parse(aliases == null ? null : aliases.get(clientModel));
        if (alias.missing()) {
            return new Alias(fallbackProvider, clientModel);
        }
        return new Alias(alias.pinsProvider() ? alias.provider() : fallbackProvider, alias.model());
    }

    /**
     * 站在指定通道上看这个模型叫什么。
     * <p>
     * 别名把通道钉成了别的值时，说明这个别名不是给本通道准备的，不做改名——
     * 否则会拿"某个通道专属的模型名"去另一个通道上查指标，查出来必然是空的。
     */
    public static String modelNameOn(Map<String, String> aliases, String clientModel, String provider) {
        Alias alias = parse(aliases == null ? null : aliases.get(clientModel));
        if (alias.missing()) {
            return clientModel;
        }
        if (alias.pinsProvider() && !alias.provider().equals(provider)) {
            return clientModel;
        }
        return alias.model();
    }
}
