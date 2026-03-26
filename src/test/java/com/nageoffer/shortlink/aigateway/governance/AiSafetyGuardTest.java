package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

class AiSafetyGuardTest {

    @Test
    void shouldBlockInputWhenHitRule() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getSafety().setEnabled(true);
        properties.getSafety().setBlockedWords(Set.of("forbidden"));
        AiSafetyGuard guard = new AiSafetyGuard(properties);

        Assertions.assertThrows(AiGatewayClientException.class, () -> guard.verifyInput("this is forbidden content"));
    }

    @Test
    void shouldReplaceOutputWhenStrategyIsReplace() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getSafety().setEnabled(true);
        properties.getSafety().setOutputStrategy("replace");
        properties.getSafety().setBlockedWords(Set.of("secret"));
        AiSafetyGuard guard = new AiSafetyGuard(properties);

        String output = guard.processOutput("contains secret message");
        Assertions.assertEquals("contains *** message", output);
    }
}
