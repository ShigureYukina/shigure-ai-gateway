package com.nageoffer.shortlink.aigateway.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 出站 WebClient 装配。
 * <p>
 * 两个 Bean 是同一套连接层参数（连接超时 / 读超时 / 响应超时 / 不跟随重定向），
 * 只在<b>响应体内存上限</b>上分开：
 * <ul>
 *   <li>{@code aiGatewayWebClient}（{@link Primary}）：承载数据面的模型响应。非流式大响应
 *       （长上下文 + 高 max_tokens）可能到几 MB，而 WebFlux 默认上限只有 256KB ——
 *       低于它的响应会直接抛 {@code DataBufferLimitException}，表现为"上游 200 但网关 500"；</li>
 *   <li>{@code aiMetadataWebClient}：只用于"列出模型"和"拉价格表"这类元数据请求，
 *       响应本该是几百 KB 的清单。这里主动压到 2MB：这两个入口的地址可以是运维填的，
 *       一旦填错或被攻陷，低上限能把内存占用与"把外部内容整个读进堆"的影响限制住。</li>
 * </ul>
 * 两者都不跟随重定向：跟随等于让上游替我们指定下一个请求目标，
 * SSRF 校验（{@code OutboundUrlValidator}）就白做了 —— 默认就是 false，写出来是为了防回归。
 */
@Configuration
public class WebClientConfiguration {

    /**
     * 数据面响应体上限。取 16MB 是"正常大响应一定够、异常大响应一定拦住"的折中：
     * 4000 token 上下的响应在几十 KB 量级，16MB 留了两个数量级余量。
     */
    private static final int GATEWAY_MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024;

    /**
     * 元数据响应体上限，见类注释。
     */
    private static final int METADATA_MAX_IN_MEMORY_BYTES = 2 * 1024 * 1024;

    @Bean
    @Primary
    public WebClient aiGatewayWebClient(WebClient.Builder builder, AiGatewayProperties properties) {
        return builder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient(properties)))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(GATEWAY_MAX_IN_MEMORY_BYTES))
                .build();
    }

    @Bean("aiMetadataWebClient")
    public WebClient aiMetadataWebClient(WebClient.Builder builder, AiGatewayProperties properties) {
        return builder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient(properties)))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(METADATA_MAX_IN_MEMORY_BYTES))
                .build();
    }

    private HttpClient httpClient(AiGatewayProperties properties) {
        Duration readTimeout = properties.getTimeoutRetry().getReadTimeout();
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.getTimeoutRetry().getConnectTimeout().toMillis()))
                .doOnConnected(connection -> connection.addHandlerLast(
                        new ReadTimeoutHandler(readTimeout.toSeconds(), TimeUnit.SECONDS)))
                // ReadTimeoutHandler 只管"连接空闲"：上游收了请求却一直不回包、也不断开时它不会触发，
                // 表现为连接被无限期占住。responseTimeout 补的就是这一段。
                .responseTimeout(readTimeout)
                .followRedirect(false);
    }
}
