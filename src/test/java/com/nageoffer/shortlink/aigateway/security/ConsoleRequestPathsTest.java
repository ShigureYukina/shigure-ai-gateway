package com.nageoffer.shortlink.aigateway.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据面清单必须精确匹配：宽松匹配会把 {@code /v1/providers/models} 这类管理面端点误放行。
 */
class ConsoleRequestPathsTest {

    @Test
    void shouldRecogniseEveryDataPlaneEndpoint() {
        assertTrue(ConsoleRequestPaths.isDataPlane("/v1/chat/completions"));
        assertTrue(ConsoleRequestPaths.isDataPlane("/v1/models"));
        assertTrue(ConsoleRequestPaths.isDataPlane("/v1/messages"));
        assertTrue(ConsoleRequestPaths.isDataPlane("/v1/messages/count_tokens"));
        assertTrue(ConsoleRequestPaths.isDataPlane("/v1/responses"));
    }

    @Test
    void shouldNotTreatManagementPathsAsDataPlane() {
        assertFalse(ConsoleRequestPaths.isDataPlane("/v1/providers/models"));
        assertFalse(ConsoleRequestPaths.isDataPlane("/v1/observability/models/gpt-4o"));
        assertFalse(ConsoleRequestPaths.isDataPlane("/v1/tenant-config/api-keys/sk-demo"));
    }

    @Test
    void shouldNotTreatDataPlanePrefixesWithExtraSegmentsAsDataPlane() {
        assertFalse(ConsoleRequestPaths.isDataPlane("/v1/chat/completions/extra"));
        assertFalse(ConsoleRequestPaths.isDataPlane("/v1/messages/extra"));
        assertFalse(ConsoleRequestPaths.isDataPlane("/v1/models/gpt-4o"));
    }

    @Test
    void shouldRejectBlankAndNonV1Paths() {
        assertFalse(ConsoleRequestPaths.isDataPlane(null));
        assertFalse(ConsoleRequestPaths.isDataPlane(""));
        assertFalse(ConsoleRequestPaths.isDataPlane("/"));
        assertFalse(ConsoleRequestPaths.isDataPlane("/v1/"));
    }

    @Test
    void shouldOnlyAllowTheExactLoginPath() {
        assertTrue(ConsoleRequestPaths.isLogin("/v1/security/login"));
        assertFalse(ConsoleRequestPaths.isLogin("/v1/security/logout"));
        assertFalse(ConsoleRequestPaths.isLogin("/v1/security/config"));
        assertFalse(ConsoleRequestPaths.isLogin("/v1/security/login/extra"));
        assertFalse(ConsoleRequestPaths.isLogin(null));
    }
}
