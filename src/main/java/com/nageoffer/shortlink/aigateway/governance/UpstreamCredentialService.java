package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.persistence.service.TenantConfigQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;

/**
 * 上游凭证解析。
 * <p>
 * 解析顺序：租户 BYOK -> 平台级凭证。被禁用或 Key 为空的凭证视为未配置，
 * 两者皆无时快速失败，避免把"未配置上游 Key"伪装成上游 401 返回给客户端。
 */
@Component
@RequiredArgsConstructor
public class UpstreamCredentialService {

    private final TenantConfigQueryService tenantConfigQueryService;

    private final ProviderKeyPoolService providerKeyPoolService;

    /**
     * 解析可用凭证：租户 BYOK 优先，未命中回退平台级。
     */
    public Optional<AiGatewayProperties.ProviderCredential> resolve(String tenantId, String provider) {
        if (StringUtils.hasText(tenantId)) {
            Optional<AiGatewayProperties.ProviderCredential> tenantScoped = tenantConfigQueryService
                    .findProviderCredential(tenantId, provider)
                    .filter(this::usable);
            if (tenantScoped.isPresent()) {
                return tenantScoped;
            }
        }
        return tenantConfigQueryService.findGlobalProviderCredential(provider).filter(this::usable);
    }

    public AiGatewayProperties.ProviderCredential require(String tenantId, String provider) {
        return resolve(tenantId, provider)
                .orElseThrow(() -> new AiGatewayClientException(AiGatewayErrorCode.UPSTREAM_CREDENTIAL_MISSING,
                        "未配置上游凭证: provider=" + provider + ", tenantId=" + tenantId));
    }

    /**
     * 将上游凭证写入转发头。
     * <p>
     * 客户端传入的 Authorization 属于平台身份，不再向下游透传；
     * 上游只接受这里注入的凭证与 provider 附加头。
     */
    public CredentialLease applyCredential(HttpHeaders headers, String provider, String tenantId) {
        AiGatewayProperties.ProviderCredential credential = require(tenantId, provider);
        // Key 池优先（DB 优先，其次 yml 的 api-keys）：配了池就以池为准，否则沿用单 Key
        AiGatewayProperties.ProviderApiKey selected = providerKeyPoolService
                .select(tenantId, provider, tenantConfigQueryService.findProviderKeys(tenantId, provider));
        String apiKey = selected == null ? credential.getApiKey() : selected.getApiKey();
        String keyId = selected == null ? "primary" : providerKeyPoolService.keyIdOf(selected);

        String headerName = StringUtils.hasText(credential.getAuthHeader())
                ? credential.getAuthHeader().trim()
                : HttpHeaders.AUTHORIZATION;
        String scheme = credential.getAuthScheme();
        headers.set(headerName, StringUtils.hasText(scheme) ? scheme.trim() + " " + apiKey : apiKey);
        Map<String, String> extraHeaders = credential.getExtraHeaders();
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            extraHeaders.forEach((name, value) -> {
                if (StringUtils.hasText(name) && StringUtils.hasText(value)) {
                    headers.set(name.trim(), value.trim());
                }
            });
        }
        return new CredentialLease(tenantId, provider, keyId);
    }

    /**
     * 把一次调用的结果反馈给 Key 池，用于把"打坏的 Key"暂时摘出去。
     */
    public void reportOutcome(CredentialLease lease, boolean success, Throwable error) {
        if (lease == null) {
            return;
        }
        if (success) {
            providerKeyPoolService.reportSuccess(lease.tenantId(), lease.provider(), lease.keyId());
        } else {
            providerKeyPoolService.reportFailure(lease.tenantId(), lease.provider(), lease.keyId(), error);
        }
    }

    /**
     * 凭证是否可用：单 Key 与 Key 池任一有货即可，否则视为未配置。
     */
    private boolean usable(AiGatewayProperties.ProviderCredential credential) {
        if (credential == null || !credential.isEnabled()) {
            return false;
        }
        if (StringUtils.hasText(credential.getApiKey())) {
            return true;
        }
        return credential.getApiKeys() != null && credential.getApiKeys().stream()
                .anyMatch(key -> key != null && key.isEnabled() && StringUtils.hasText(key.getApiKey()));
    }

    /**
     * 一次上游调用实际使用的凭证标识。
     * <p>
     * 带上 keyId 是为了让失败可以精确归因到某一把 Key——
     * 只按 provider 记失败，多 Key 轮换就永远学不到"哪把钥匙坏了"。
     */
    public record CredentialLease(String tenantId, String provider, String keyId) {
    }
}
