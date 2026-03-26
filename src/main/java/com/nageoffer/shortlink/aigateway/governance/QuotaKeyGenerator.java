package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class QuotaKeyGenerator {

    private final AiGatewayProperties properties;

    public String build(HttpHeaders headers, String provider, String providerModel) {
        return build(null, headers, provider, providerModel);
    }

    public String build(TenantContext tenantContext, HttpHeaders headers, String provider, String providerModel) {
        List<String> segments = new ArrayList<>();
        segments.add("provider=" + provider);
        segments.add("model=" + providerModel);
        TenantContext effectiveTenantContext = resolveTenantContext(tenantContext);
        segments.add("tenantId=" + effectiveTenantContext.tenantId());
        segments.add("appId=" + effectiveTenantContext.appId());
        segments.add("keyId=" + effectiveTenantContext.keyId());
        for (String dimension : properties.getRateLimit().getKeyDimensions()) {
            String value = resolveDimensionValue(dimension, headers);
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

    private String resolveDimensionValue(String dimension, HttpHeaders headers) {
        return switch (dimension) {
            case "userId" -> getHeaderOrDefault(headers, "userId", "anonymous");
            case "ip" -> {
                String forwarded = headers.getFirst("X-Forwarded-For");
                if (StringUtils.hasText(forwarded)) {
                    yield forwarded.split(",")[0].trim();
                }
                yield getHeaderOrDefault(headers, "X-Real-IP", "unknown");
            }
            case "consumer" -> getHeaderOrDefault(headers, "X-Consumer", "default");
            default -> getHeaderOrDefault(headers, dimension, "na");
        };
    }

    private String getHeaderOrDefault(HttpHeaders headers, String headerName, String defaultValue) {
        String value = headers.getFirst(headerName);
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
