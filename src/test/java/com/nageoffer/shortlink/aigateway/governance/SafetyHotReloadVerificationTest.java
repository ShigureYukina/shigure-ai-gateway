package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.controller.AiSafetyController;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class SafetyHotReloadVerificationTest {

    @Test
    void shouldTakeEffectImmediatelyAfterSafetyConfigUpdated() {
        AiGatewayProperties properties = new AiGatewayProperties();
        AiSafetyGuard guard = new AiSafetyGuard(properties);
        AiSafetyController controller = new AiSafetyController(properties);

        controller.update(Map.of(
                "enabled", true,
                "outputStrategy", "intercept",
                "blockedWords", List.of("forbidden")
        ));

        Assertions.assertThrows(AiGatewayClientException.class, () -> guard.verifyInput("contains forbidden text"));

        controller.update(Map.of(
                "enabled", true,
                "outputStrategy", "replace",
                "blockedWords", List.of("forbidden")
        ));

        String processed = guard.processOutput("reply with forbidden token");
        Assertions.assertEquals("reply with *** token", processed);
    }
}
