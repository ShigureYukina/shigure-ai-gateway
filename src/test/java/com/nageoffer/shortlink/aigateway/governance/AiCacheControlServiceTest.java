package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class AiCacheControlServiceTest {

    @Test
    void shouldDisableForStreamOrGlobalDisabled() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getCache().setEnabled(false);
        AiCacheControlService service = new AiCacheControlService(properties);

        Assertions.assertFalse(service.enabledForRequest(new HttpHeaders(), false));
        Assertions.assertFalse(service.enabledForRequest(new HttpHeaders(), true));
    }

    @Test
    void shouldRespectBypassHeaderWhenCacheEnabled() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getCache().setEnabled(true);
        AiCacheControlService service = new AiCacheControlService(properties);

        HttpHeaders normal = new HttpHeaders();
        Assertions.assertTrue(service.enabledForRequest(normal, false));

        HttpHeaders bypass = new HttpHeaders();
        bypass.set("X-Cache-Bypass", "true");
        Assertions.assertFalse(service.enabledForRequest(bypass, false));
    }
}
