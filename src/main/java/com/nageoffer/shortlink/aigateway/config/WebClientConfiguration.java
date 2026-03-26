package com.nageoffer.shortlink.aigateway.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfiguration {

    @Bean
    public WebClient aiGatewayWebClient(WebClient.Builder builder, AiGatewayProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.getTimeoutRetry().getConnectTimeout().toMillis()))
                .doOnConnected(connection -> connection.addHandlerLast(
                        new ReadTimeoutHandler(properties.getTimeoutRetry().getReadTimeout().toSeconds(), TimeUnit.SECONDS)
                ));
        return builder.clientConnector(new ReactorClientHttpConnector(httpClient)).build();
    }
}
