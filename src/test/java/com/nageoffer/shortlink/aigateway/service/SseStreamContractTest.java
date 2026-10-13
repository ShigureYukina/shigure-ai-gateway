package com.nageoffer.shortlink.aigateway.service;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayUpstreamException;
import com.nageoffer.shortlink.aigateway.governance.AiSafetyGuard;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.retry.Retry;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 流式出口的几个契约测试。
 *
 * <p><b>这个名字以前叫 SseStreamE2ETest，是错的，已改名。</b>
 * 它既不启动应用、也不连上游 mock，更没有走过 {@code AiGatewayService.chatCompletion}，
 * 叫 E2E 会让人以为流式整条链路被端到端覆盖了。真实情况是：
 * 它只用 {@link StepVerifier} 在内存里拼了几条 {@link Flux}，覆盖流式出口上的
 * 两个局部契约。
 *
 * <p>保留的两个契约：
 * <ol>
 *   <li>安全过滤在输出侧拦截违规 chunk 时，中止流并抛业务异常（而不是把违规内容放过）；</li>
 *   <li>可重试异常按 {@code isRetriable()} 过滤后重试，且重试次数有界。</li>
 * </ol>
 *
 * <p>原先还有一个 {@code shouldSupportNormalSseFlow}，内容是
 * {@code StepVerifier.create(Flux.just(...))}——它验证的是 Reactor 而不是本网关的代码，
 * 已经删掉。一个测试类的存在理由是它覆盖了某个被测对象；覆盖不到就不是测试，是陪跑。
 *
 * <p>流式的端到端覆盖目前仍然缺失，见
 * {@code docs/load/fault-injection.md} 第五节（跨供应商回退那部分本机压不出来）。
 */
class SseStreamContractTest {

    @Test
    void shouldAbortWhenSseChunkViolatesSafetyRule() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getSafety().setEnabled(true);
        properties.getSafety().setOutputStrategy("intercept");
        properties.getSafety().setBlockedWords(Set.of("forbidden"));
        AiSafetyGuard guard = new AiSafetyGuard(properties);

        Flux<String> stream = Flux.just("data: safe\\n\\n", "data: forbidden\\n\\n")
                .map(guard::processOutput);

        StepVerifier.create(stream)
                .expectNext("data: safe\\n\\n")
                .expectError(AiGatewayClientException.class)
                .verify();
    }

    @Test
    void shouldRespectRetryBoundaryForRetriableSseFailure() {
        AtomicInteger attempts = new AtomicInteger();
        Flux<String> stream = Flux.defer(() -> {
                    if (attempts.incrementAndGet() < 3) {
                        return Flux.error(new AiGatewayUpstreamException(503, "temporary", true));
                    }
                    return Flux.just("data: recovered\\n\\n");
                })
                .retryWhen(Retry.max(2).filter(ex -> ex instanceof AiGatewayUpstreamException upstream && upstream.isRetriable()));

        StepVerifier.create(stream)
                .expectNext("data: recovered\\n\\n")
                .verifyComplete();
        // 断言次数而不是只看结果：只看结果的话，"重试 0 次直接成功"和"重试满 2 次后成功"
        // 会给出同样的输出，这条测试就区分不出 retryWhen 有没有真的接上。
        assertEquals(3, attempts.get());
    }
}
