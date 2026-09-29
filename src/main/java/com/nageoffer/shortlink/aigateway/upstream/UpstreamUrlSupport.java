package com.nageoffer.shortlink.aigateway.upstream;

import java.net.URI;

/**
 * 上游端点地址拼装。
 * <p>
 * 收敛点：baseUrl 归一化、"baseUrl + path" 拼接这两件事原先分散在
 * {@code AiProviderController}、{@code ModelCatalogSyncService}、{@code ProviderRoutingService} 三处，
 * 各写一遍且语义不一致（有的校验主机名、有的不校验；有的补前导斜杠、有的不补）。
 * 这里只放"纯字符串约定"，不含任何配置或凭证逻辑，因此任何包都可以依赖它而不成环。
 */
public final class UpstreamUrlSupport {

    /**
     * OpenAI 风格的版本段。baseUrl 与 path 各自都带上它是最自然的写法，
     * 但直接相加会得到 {@code /v1/v1/...}。
     */
    private static final String VERSION_SEGMENT = "/v1";

    private UpstreamUrlSupport() {
    }

    /**
     * 去掉首尾空白与尾部斜杠。不改动路径内容，也不校验合法性。
     */
    public static String trimBaseUrl(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * 补全前导斜杠，容忍配置写成 {@code v1/models}。
     */
    public static String normalizePath(String path) {
        String trimmed = path == null ? "" : path.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    /**
     * 拼装上游端点。
     * <p>
     * 关键约定：baseUrl 与 path <b>同时</b>带 {@code /v1} 时只保留一份。两边都是运维手写的配置，
     * 各自带上版本段是最容易写出来的形式（{@code https://api.openai.com/v1} + {@code /v1/models}），
     * 直接相加会得到 {@code /v1/v1/models}，而且表现为"每个请求都 404"这种难定位的现象。
     * <p>
     * 只在两边都出现时去重：baseUrl 不带版本段、或 path 自带别的前缀时，拼接结果与手写一致。
     */
    public static String join(String baseUrl, String path) {
        String base = trimBaseUrl(baseUrl);
        String normalizedPath = normalizePath(path);
        if (base.endsWith(VERSION_SEGMENT) && normalizedPath.startsWith(VERSION_SEGMENT + "/")) {
            base = base.substring(0, base.length() - VERSION_SEGMENT.length());
        }
        return base + normalizedPath;
    }

    /**
     * 校验并归一化 http(s) baseUrl，非法时抛 {@link IllegalArgumentException}。
     * <p>
     * 这是"什么算合法 baseUrl"的唯一定义，调用方负责把异常翻译成 4xx。
     */
    public static String requireHttpBaseUrl(String baseUrl) {
        String trimmed = trimBaseUrl(baseUrl);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("baseUrl不能为空");
        }
        URI uri = URI.create(trimmed);
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("baseUrl必须是http或https地址");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("baseUrl缺少主机名");
        }
        return trimmed;
    }
}
