package com.nageoffer.shortlink.aigateway.tenant;

public record TenantContext(String tenantId, String appId, String keyId) {

    public static TenantContext global(String tenantId, String appId, String keyId) {
        return new TenantContext(tenantId, appId, keyId);
    }
}
