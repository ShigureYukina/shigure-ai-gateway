package com.nageoffer.shortlink.aigateway.security;

import java.util.Set;

/**
 * 数据面路径的唯一清单。
 * <p>
 * 数据面用平台 API Key 鉴权（{@link ApiKeyAuthService}），管理面用控制台令牌，
 * 两套身份体系不能互相要求对方的凭证 —— 所以管理面的鉴权过滤器必须先把数据面排除掉。
 * <p>
 * 这里用**精确匹配**而不是前缀匹配：{@code /v1/models} 是数据面，而
 * {@code /v1/providers/models}（控制台"测试连接"）、{@code /v1/observability/models/...}
 * 都是管理面，一旦写成 {@code startsWith("/v1/")} 式的宽松匹配就会把它们误放行。
 */
public final class ConsoleRequestPaths {

    /**
     * 全部数据面端点，与三个数据面控制器的映射一一对应：
     * {@code AiGatewayController} / {@code AiModelsController} / {@code AiNativeProtocolController}。
     */
    private static final Set<String> DATA_PLANE = Set.of(
            "/v1/chat/completions",
            "/v1/models",
            "/v1/messages",
            "/v1/messages/count_tokens",
            "/v1/responses"
    );

    private ConsoleRequestPaths() {
    }

    public static boolean isDataPlane(String path) {
        return path != null && DATA_PLANE.contains(path);
    }

    /**
     * 登录入口永远放行：它是"把鉴权打开之后还能进去把它关掉"的唯一通道。
     */
    public static boolean isLogin(String path) {
        return "/v1/security/login".equals(path);
    }
}
