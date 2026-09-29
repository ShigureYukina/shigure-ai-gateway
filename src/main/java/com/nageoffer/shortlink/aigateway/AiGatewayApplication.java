package com.nageoffer.shortlink.aigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 网关应用启动入口。
 * <p>
 * 启动后会自动装配路由、治理、可观测与插件链等核心能力。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AiGatewayApplication {

    /**
     * Spring Boot 标准启动方法。
     */
    public static void main(String[] args) {
        SpringApplication.run(AiGatewayApplication.class, args);
    }
}
