package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 配额键生成器。
 * <p>
 * 内置维度一律取<b>服务端身份</b>，不取客户端可伪造的请求头：
 * <ul>
 *   <li>{@code userId} → 认证后的凭证 ID（keyId）；</li>
 *   <li>{@code consumer} → 认证后的应用 ID（appId）；</li>
 *   <li>{@code ip} → TCP 连接的远端地址（socket 级事实）。</li>
 * </ul>
 * 历史实现直接读 {@code userId} / {@code X-Forwarded-For} / {@code X-Consumer} 三个请求头，
 * 它们都在客户端控制之下——轮换请求头就能拿到一份全新的配额，租户聚合配额形同虚设。
 * <p>
 * 未识别的自定义维度仍从头读取：这是部署方显式写进配置的信任决定
 * （例如前置 LB 统一覆写的 {@code X-Real-IP}），网关不再替他默认。
 * <p>
 * {@code ip} 维度在反向代理后会退化为代理 IP（所有客户端共享一个桶）——
 * 这是"宁可信一个更弱的真话，不可信一个更强的假话"的取舍；
 * 需要真实客户端 IP 时应由可信代理覆写并在配置里声明对应维度。
 */
@Component
@RequiredArgsConstructor
public class QuotaKeyGenerator {

    private final AiGatewayProperties properties;

    public String build(TenantContext tenantContext, ServerHttpRequest httpRequest, String provider, String providerModel) {
        List<String> segments = new ArrayList<>();
        segments.add("provider=" + provider);
        segments.add("model=" + providerModel);
        TenantContext effectiveTenantContext = resolveTenantContext(tenantContext);
        segments.add("tenantId=" + effectiveTenantContext.tenantId());
        segments.add("appId=" + effectiveTenantContext.appId());
        segments.add("keyId=" + effectiveTenantContext.keyId());
        for (String dimension : properties.getRateLimit().getKeyDimensions()) {
            String value = resolveDimensionValue(dimension, effectiveTenantContext, httpRequest);
            segments.add(dimension + "=" + value);
        }
        return String.join("|", segments);
    }

    private TenantContext resolveTenantContext(TenantContext tenantContext) {
        if (tenantContext != null) {
            return tenantContext;
        }
        return TenantContext.global(
                properties.getTenant().getDefaultTenantId(),
                properties.getTenant().getDefaultAppId(),
                properties.getTenant().getDefaultKeyId()
        );
    }

    private String resolveDimensionValue(String dimension, TenantContext tenantContext, ServerHttpRequest httpRequest) {
        return switch (dimension) {
            case "userId" -> StringUtils.hasText(tenantContext.keyId()) ? tenantContext.keyId() : "anonymous";
            case "consumer" -> StringUtils.hasText(tenantContext.appId()) ? tenantContext.appId() : "default";
            case "ip" -> {
                InetSocketAddress remote = httpRequest.getRemoteAddress();
                yield remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
            }
            default -> getHeaderOrDefault(httpRequest.getHeaders(), dimension, "na");
        };
    }

    private String getHeaderOrDefault(HttpHeaders headers, String headerName, String defaultValue) {
        String value = headers.getFirst(headerName);
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
