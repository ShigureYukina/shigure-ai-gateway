package com.nageoffer.shortlink.aigateway.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionSecurityPropertiesValidator {

    private final AiGatewayProperties properties;

    @PostConstruct
    public void validate() {
        validateJwtSecret();
        validateUserPassword("admin");
        validateUserPassword("viewer");
        validateNacosAddress();
    }

    private void validateJwtSecret() {
        String jwtSecret = properties.getSecurity().getJwtSecret();
        if (!StringUtils.hasText(jwtSecret) || jwtSecret.length() < 32
                || "replace-with-at-least-32-char-secret-key".equals(jwtSecret)) {
            throw new IllegalStateException("prod 环境要求设置有效的 AI_GATEWAY_JWT_SECRET，且长度至少 32 字符");
        }
    }

    private void validateUserPassword(String username) {
        AiGatewayProperties.UserCredential credential = properties.getSecurity().getUsers().get(username);
        String password = credential == null ? null : credential.getPassword();
        if (!StringUtils.hasText(password) || password.length() < 8
                || "admin123456".equals(password) || "viewer123456".equals(password)) {
            throw new IllegalStateException("prod 环境要求为用户 " + username + " 设置非默认且长度至少 8 的密码");
        }
    }

    private void validateNacosAddress() {
        String nacosAddr = System.getProperty("spring.cloud.nacos.discovery.server-addr");
        if (!StringUtils.hasText(nacosAddr)) {
            nacosAddr = System.getenv("NACOS_SERVER_ADDR");
        }
        if (!StringUtils.hasText(nacosAddr)) {
            throw new IllegalStateException("prod 环境要求设置 NACOS_SERVER_ADDR");
        }
    }
}
