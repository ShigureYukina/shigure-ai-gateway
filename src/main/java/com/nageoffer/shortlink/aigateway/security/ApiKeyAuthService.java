package com.nageoffer.shortlink.aigateway.security;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
public class ApiKeyAuthService {

    private static final String API_KEY_HEADER = "x-api-key";

    private final AiGatewayProperties properties;

    private final TenantConfigQueryService tenantConfigQueryService;

    @Autowired
    public ApiKeyAuthService(AiGatewayProperties properties, TenantConfigQueryService tenantConfigQueryService) {
        this.properties = properties;
        this.tenantConfigQueryService = tenantConfigQueryService;
    }

    public ApiKeyAuthService(AiGatewayProperties properties) {
        this(properties, TenantConfigQueryService.fallbackOnly(properties));
    }

    public TenantContext authenticate(HttpHeaders headers) {
        if (!properties.getTenant().isEnabled()) {
            return TenantContext.global(
                    properties.getTenant().getDefaultTenantId(),
                    properties.getTenant().getDefaultAppId(),
                    properties.getTenant().getDefaultKeyId());
        }
        String apiKey = resolveApiKey(headers);
        if (!StringUtils.hasText(apiKey)) {
            throw new AiGatewayClientException(AiGatewayErrorCode.UNAUTHORIZED, "缺少平台 API Key");
        }
        AiGatewayProperties.TenantApiKeyCredential credential = findCredential(apiKey)
                .orElseThrow(() -> new AiGatewayClientException(AiGatewayErrorCode.UNAUTHORIZED, "API Key 无效或未绑定租户"));
        if (!credential.isEnabled()) {
            throw new AiGatewayClientException(AiGatewayErrorCode.UNAUTHORIZED, "API Key 已禁用");
        }
        if (credential.getExpiresAt() != null && credential.getExpiresAt().isBefore(Instant.now())) {
            throw new AiGatewayClientException(AiGatewayErrorCode.UNAUTHORIZED, "API Key 已过期");
        }
        if (!StringUtils.hasText(credential.getTenantId()) || !StringUtils.hasText(credential.getAppId())) {
            throw new AiGatewayClientException(AiGatewayErrorCode.UNAUTHORIZED, "API Key 无效或未绑定租户");
        }
        String keyId = StringUtils.hasText(credential.getKeyId()) ? credential.getKeyId() : resolveCredentialKeyId(apiKey);
        return new TenantContext(credential.getTenantId(), credential.getAppId(), keyId);
    }

    private String resolveApiKey(HttpHeaders headers) {
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        String apiKey = headers.getFirst(API_KEY_HEADER);
        return StringUtils.hasText(apiKey) ? apiKey.trim() : null;
    }

    private Optional<AiGatewayProperties.TenantApiKeyCredential> findCredential(String apiKey) {
        return tenantConfigQueryService.findApiKeyCredential(apiKey);
    }

    private String resolveCredentialKeyId(String apiKey) {
        for (Map.Entry<String, AiGatewayProperties.TenantApiKeyCredential> entry : properties.getTenant().getApiKeys().entrySet()) {
            if (apiKey.equals(entry.getValue().getApiKey())) {
                return entry.getKey();
            }
        }
        return properties.getTenant().getDefaultKeyId();
    }
}
