package com.nageoffer.shortlink.aigateway.governance;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionMessage;
import com.nageoffer.shortlink.aigateway.dto.req.AiChatCompletionReqDTO;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.observability.AiGatewayMetricsRecorder;
import com.nageoffer.shortlink.aigateway.tenant.TenantContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.anyList;

class RedisTokenQuotaConsistencyTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldKeepQuotaConsistentUnderConcurrentMultiInstanceLikeRequests() throws Exception {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setDefaultTokenQuotaPerMinute(1000L);
        properties.getRateLimit().setDefaultTokenQuotaPerDay(10000L);
        properties.getRateLimit().setMinTokenReserve(100L);

        ReactiveStringRedisTemplate redisTemplate = Mockito.mock(ReactiveStringRedisTemplate.class);
        AtomicLong minuteUsage = new AtomicLong(0L);
        AtomicLong dayUsage = new AtomicLong(0L);

        // 预检脚本的新契约：回传 {allowed, minuteAfterReserve, dayAfterReserve}，
        // 服务层靠第二个/第三个元素在预检时刻组装限流响应头
        Mockito.when(redisTemplate.execute(ArgumentMatchers.<RedisScript<List>>any(), anyList(), anyList()))
                .thenAnswer(invocation -> {
                    List<String> args = invocation.getArgument(2);
                    long reserve = Long.parseLong(args.get(0));
                    long minuteLimit = Long.parseLong(args.get(1));
                    long dayLimit = Long.parseLong(args.get(2));
                    long monthLimit = Long.parseLong(args.get(3));
                    synchronized (this) {
                        if (minuteUsage.get() + reserve > minuteLimit || dayUsage.get() + reserve > dayLimit || dayUsage.get() + reserve > monthLimit) {
                            return Flux.just(List.of(0L, minuteUsage.get(), dayUsage.get()));
                        }
                        minuteUsage.addAndGet(reserve);
                        dayUsage.addAndGet(reserve);
                        return Flux.just(List.of(1L, minuteUsage.get(), dayUsage.get()));
                    }
                });

        TokenEstimator tokenEstimator = new TokenEstimator(properties);
        QuotaKeyGenerator quotaKeyGenerator = new QuotaKeyGenerator(properties);
        RedisTokenQuotaService service = new RedisTokenQuotaService(redisTemplate, tokenEstimator, quotaKeyGenerator, properties, Mockito.mock(AiGatewayMetricsRecorder.class));

        ServerHttpRequest httpRequest = MockServerHttpRequest.post("/v1/chat/completions")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 50000))
                .build();

        AiChatCompletionReqDTO request = new AiChatCompletionReqDTO();
        request.setModel("gpt-4o-mini");
        request.setMaxTokens(90);
        AiChatCompletionMessage message = new AiChatCompletionMessage();
        message.setRole("user");
        message.setContent("this is a test message for quota consistency");
        request.setMessages(List.of(message));

        int parallelRequests = 30;
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(parallelRequests);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong rejectedCount = new AtomicLong(0);

        for (int i = 0; i < parallelRequests; i++) {
            executor.submit(() -> {
                try {
                    service.preCheck(new TenantContext("tenant-a", "app-a", "key-a"), httpRequest, "openai", "gpt-4o-mini", request).block();
                    successCount.incrementAndGet();
                } catch (AiGatewayClientException ex) {
                    rejectedCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        Assertions.assertTrue(successCount.get() > 0);
        Assertions.assertTrue(rejectedCount.get() > 0);
        Assertions.assertTrue(minuteUsage.get() <= properties.getRateLimit().getDefaultTokenQuotaPerMinute());
    }
}
