package com.nageoffer.shortlink.aigateway.routing;

/**
 * 渠道可用性视图：路由在每请求热路径上问的唯一问题——"这个渠道现在允许被选中吗"。
 * <p>
 * 抽成接口而不是直接依赖 {@link ChannelHealthRegistry}，是为了让路由侧的测试与
 * "没有 DB / 没开探测"的场景能传一个固定答案进来（{@link #allowAll()}），
 * 而不必构造一个真的注册表。
 * <p>
 * 语义上它是<b>单向的否决权</b>：{@code allows} 返回 false 表示"别选它"，
 * 返回 true 只是"没理由拦它"，不代表它一定可用（还得看 baseUrl、凭证等）。
 * 这样探测的结论只能缩窄候选集，不会凭空造出一个不可用的候选。
 */
public interface ChannelHealthView {

    /**
     * 允许（放行）所有渠道。
     */
    ChannelHealthView ALLOW_ALL = provider -> true;

    /**
     * 默认实现：不做任何过滤。等价于"探测未启用"的旧行为。
     */
    static ChannelHealthView allowAll() {
        return ALLOW_ALL;
    }

    boolean allows(String provider);
}
