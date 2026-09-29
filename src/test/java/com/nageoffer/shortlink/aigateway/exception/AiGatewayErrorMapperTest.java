package com.nageoffer.shortlink.aigateway.exception;

import com.nageoffer.shortlink.aigateway.dto.resp.AiGatewayErrorRespDTO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiGatewayErrorMapperTest {

    @Test
    void shouldMapQuotaExceededToRateLimitError() {
        AiGatewayErrorRespDTO response = AiGatewayErrorMapper.toResponse(
                new AiGatewayClientException(AiGatewayErrorCode.QUOTA_EXCEEDED, "配额不足"));

        Assertions.assertEquals(429, response.getStatus());
        Assertions.assertEquals("rate_limit_error", response.getError().getType());
        Assertions.assertEquals("insufficient_quota", response.getError().getCode());
        Assertions.assertEquals("配额不足", response.getMessage());
    }

    @Test
    void shouldPassThroughUpstreamClientErrorStatus() {
        AiGatewayErrorRespDTO response = AiGatewayErrorMapper.toResponse(
                new AiGatewayUpstreamException(400, "上游参数错误", false));

        Assertions.assertEquals(400, response.getStatus());
        Assertions.assertEquals("api_error", response.getError().getType());
        Assertions.assertEquals("upstream_error", response.getError().getCode());
    }

    @Test
    void shouldCollapseUpstreamServerErrorToBadGateway() {
        AiGatewayErrorRespDTO response = AiGatewayErrorMapper.toResponse(
                new AiGatewayUpstreamException(503, "上游不可用", true));

        Assertions.assertEquals(502, response.getStatus());
    }

    @Test
    void shouldMapUnknownExceptionToInternalApiError() {
        AiGatewayErrorRespDTO response = AiGatewayErrorMapper.toResponse(new IllegalStateException("boom"));

        Assertions.assertEquals(500, response.getStatus());
        Assertions.assertEquals("api_error", response.getError().getType());
        Assertions.assertEquals("internal_error", response.getError().getCode());
    }

    @Test
    void shouldBuildOpenAiCompatibleErrorFrameForStreaming() {
        String frame = AiGatewayErrorMapper.toErrorFramePayload(
                new AiGatewayClientException(AiGatewayErrorCode.UPSTREAM_CREDENTIAL_MISSING, "未配置上游凭证"));

        Assertions.assertTrue(frame.startsWith("{\"error\""));
        Assertions.assertTrue(frame.contains("\"type\":\"api_error\""));
        Assertions.assertTrue(frame.contains("\"code\":\"upstream_credential_missing\""));
        // 流式客户端按 chunk 解析，帧内不应出现网关历史字段
        Assertions.assertFalse(frame.contains("\"status\""));
    }
}
