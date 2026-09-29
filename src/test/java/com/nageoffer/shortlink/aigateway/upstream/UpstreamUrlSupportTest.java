package com.nageoffer.shortlink.aigateway.upstream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UpstreamUrlSupportTest {

    @Test
    void shouldKeepSingleVersionSegmentWhenBothSidesHaveIt() {
        // baseUrl 带 /v1、path 又是 /v1/models 是最自然的配置写法，直接相加会得到 /v1/v1/models
        Assertions.assertEquals("https://api.openai.com/v1/models",
                UpstreamUrlSupport.join("https://api.openai.com/v1", "/v1/models"));
        // 尾部斜杠不能影响判定
        Assertions.assertEquals("https://api.openai.com/v1/chat/completions",
                UpstreamUrlSupport.join("https://api.openai.com/v1/", "/v1/chat/completions"));
    }

    @Test
    void shouldNotRewriteWhenOnlyOneSideHasVersionSegment() {
        Assertions.assertEquals("https://api.openai.com/v1/models",
                UpstreamUrlSupport.join("https://api.openai.com", "/v1/models"));
        // path 不带版本段时，baseUrl 自带的 /v1 必须保留
        Assertions.assertEquals("https://api.openai.com/v1/models",
                UpstreamUrlSupport.join("https://api.openai.com/v1", "/models"));
        // 只对 /v1/ 精确去重：/v1beta 不能被误伤
        Assertions.assertEquals("https://host/v1/v1beta/models",
                UpstreamUrlSupport.join("https://host/v1", "/v1beta/models"));
    }

    @Test
    void shouldOnlyStripTheVersionSegmentItselfWhenBaseUrlHasPathPrefix() {
        Assertions.assertEquals("https://host/openai/v1/models",
                UpstreamUrlSupport.join("https://host/openai/v1", "/v1/models"));
    }

    @Test
    void shouldNormalizePathAndBaseUrl() {
        Assertions.assertEquals("/v1/models", UpstreamUrlSupport.normalizePath("v1/models"));
        Assertions.assertEquals("/v1/models", UpstreamUrlSupport.normalizePath("  /v1/models  "));
        Assertions.assertEquals("", UpstreamUrlSupport.normalizePath(null));
        Assertions.assertEquals("https://host", UpstreamUrlSupport.trimBaseUrl(" https://host/ "));
        Assertions.assertEquals("https://host", UpstreamUrlSupport.trimBaseUrl("https://host///"));
    }

    @Test
    void shouldRejectBaseUrlThatIsNotHttpAddress() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> UpstreamUrlSupport.requireHttpBaseUrl("  "));
        Assertions.assertThrows(IllegalArgumentException.class, () -> UpstreamUrlSupport.requireHttpBaseUrl(null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> UpstreamUrlSupport.requireHttpBaseUrl("ftp://host"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> UpstreamUrlSupport.requireHttpBaseUrl("https://"));
    }

    @Test
    void shouldReturnTrimmedBaseUrlWhenValid() {
        Assertions.assertEquals("https://api.openai.com",
                UpstreamUrlSupport.requireHttpBaseUrl(" https://api.openai.com/ "));
        Assertions.assertEquals("http://localhost:8080/v1",
                UpstreamUrlSupport.requireHttpBaseUrl("http://localhost:8080/v1"));
    }
}
