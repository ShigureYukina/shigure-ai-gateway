package com.nageoffer.shortlink.aigateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI aiGatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ShortLink AI Gateway API")
                        .description("轻量 AI API 网关接口文档（OpenAI 兼容入口、多厂商代理、治理与观测）")
                        .version("v1")
                        .contact(new Contact()
                                .name("shortlink")
                                .url("https://github.com")
                        )
                        .license(new License().name("Apache 2.0"))
                );
    }
}
