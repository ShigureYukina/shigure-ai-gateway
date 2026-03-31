package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisSemanticCacheServiceTest {

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOps;
    private ZSetOperations<String, String> zSetOps;
    private AiGatewayProperties properties;
    private RedisSemanticCacheService service;

    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        zSetOps = mock(ZSetOperations.class);
        properties = new AiGatewayProperties();
        properties.getCache().setSemanticCacheEnabled(true);
        properties.getCache().setTtl(Duration.ofMinutes(2));
        properties.getCache().setSemanticSimilarityThreshold(0.85);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);

        service = new RedisSemanticCacheService(stringRedisTemplate, properties);
    }

    @Test
    void shouldReturnEmptyWhenCacheDisabled() {
        properties.getCache().setSemanticCacheEnabled(false);
        Optional<String> result = service.find("openai", "gpt-4o", request("hello"));
        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenProviderBlank() {
        Optional<String> result = service.find("", "gpt-4o", request("hello"));
        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenModelBlank() {
        Optional<String> result = service.find("openai", "", request("hello"));
        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenRequestNull() {
        Optional<String> result = service.find("openai", "gpt-4o", null);
        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenMessagesEmpty() {
        AiChatCompletionReqDTO req = new AiChatCompletionReqDTO();
        req.setMessages(List.of());
        Optional<String> result = service.find("openai", "gpt-4o", req);
        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnExactMatchWhenCacheHit() {
        AiChatCompletionReqDTO req = request("hello world");
        when(valueOps.get(anyString())).thenReturn("cached response");

        Optional<String> result = service.find("openai", "gpt-4o", req);

        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals("cached response", result.get());
    }

    @Test
    void shouldReturnEmptyWhenNoExactMatchAndNoIndex() {
        AiChatCompletionReqDTO req = request("hello world");
        when(valueOps.get(anyString())).thenReturn(null);
        when(zSetOps.reverseRange(anyString(), eq(0L), eq(-1L))).thenReturn(null);

        Optional<String> result = service.find("openai", "gpt-4o", req);

        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenIndexEmpty() {
        AiChatCompletionReqDTO req = request("hello world");
        when(valueOps.get(anyString())).thenReturn(null);
        when(zSetOps.reverseRange(anyString(), eq(0L), eq(-1L))).thenReturn(Set.of());

        Optional<String> result = service.find("openai", "gpt-4o", req);

        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    void shouldPutWithCorrectKeyAndTtl() {
        AiChatCompletionReqDTO req = request("test prompt");
        when(valueOps.get(anyString())).thenReturn(null);
        when(zSetOps.reverseRange(anyString(), eq(0L), eq(-1L))).thenReturn(Set.of());

        service.put("openai", "gpt-4o", req, "test response");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

        Assertions.assertTrue(keyCaptor.getValue().startsWith("short-link:ai-gateway:semantic:openai:gpt-4o:"));
        Assertions.assertEquals("test response", valueCaptor.getValue());
        Assertions.assertEquals(Duration.ofMinutes(2), ttlCaptor.getValue());
    }

    @Test
    void shouldNotPutWhenResponseBlank() {
        service.put("openai", "gpt-4o", request("test"), "");
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldNotPutWhenCacheDisabled() {
        properties.getCache().setSemanticCacheEnabled(false);
        service.put("openai", "gpt-4o", request("test"), "response");
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldNotPutWhenProviderBlank() {
        service.put("", "gpt-4o", request("test"), "response");
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldNormalizeContentToLowercaseAndCollapseWhitespace() {
        AiChatCompletionReqDTO req = new AiChatCompletionReqDTO();
        AiChatCompletionMessage msg = new AiChatCompletionMessage();
        msg.setRole("user");
        msg.setContent("  HELLO   WORLD  ");
        req.setMessages(List.of(msg));

        when(valueOps.get(anyString())).thenReturn(null);
        when(zSetOps.reverseRange(anyString(), eq(0L), eq(-1L))).thenReturn(Set.of());

        service.put("openai", "gpt-4o", req, "response");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCaptor.capture(), eq("response"), any(Duration.class));
        Assertions.assertTrue(keyCaptor.getValue().startsWith("short-link:ai-gateway:semantic:"));
    }

    @Test
    void shouldOnlyIncludeUserRoleMessages() {
        AiChatCompletionReqDTO req = new AiChatCompletionReqDTO();
        AiChatCompletionMessage userMsg = new AiChatCompletionMessage();
        userMsg.setRole("user");
        userMsg.setContent("user content");
        AiChatCompletionMessage systemMsg = new AiChatCompletionMessage();
        systemMsg.setRole("system");
        systemMsg.setContent("system content");
        req.setMessages(List.of(userMsg, systemMsg));

        when(valueOps.get(anyString())).thenReturn(null);
        when(zSetOps.reverseRange(anyString(), eq(0L), eq(-1L))).thenReturn(Set.of());

        service.put("openai", "gpt-4o", req, "response");

        verify(valueOps).set(anyString(), eq("response"), any(Duration.class));
    }

    @Test
    void shouldAddToIndexSortedSet() {
        AiChatCompletionReqDTO req = request("test prompt");
        when(valueOps.get(anyString())).thenReturn(null);
        when(zSetOps.reverseRange(anyString(), eq(0L), eq(-1L))).thenReturn(Set.of());

        service.put("openai", "gpt-4o", req, "response");

        verify(zSetOps).add(anyString(), anyString(), anyDouble());
        verify(stringRedisTemplate).expire(anyString(), any(Duration.class));
    }

    @Test
    void shouldReturnEmptyWhenSimilarityBelowThreshold() {
        AiChatCompletionReqDTO req = request("completely different text");
        when(valueOps.get(anyString())).thenReturn(null);

        LinkedHashSet<String> indexEntries = new LinkedHashSet<>();
        indexEntries.add("somehash::" + java.util.Base64.getUrlEncoder().encodeToString("unrelated content here".getBytes()));
        when(zSetOps.reverseRange(anyString(), eq(0L), eq(-1L))).thenReturn(indexEntries);

        Optional<String> result = service.find("openai", "gpt-4o", req);

        Assertions.assertTrue(result.isEmpty());
    }

    private AiChatCompletionReqDTO request(String content) {
        AiChatCompletionReqDTO req = new AiChatCompletionReqDTO();
        AiChatCompletionMessage msg = new AiChatCompletionMessage();
        msg.setRole("user");
        msg.setContent(content);
        req.setMessages(List.of(msg));
        return req;
    }
}
