package com.nageoffer.shortlink.aigateway.config;

import com.nageoffer.shortlink.aigateway.crypto.MasterKey;
import com.nageoffer.shortlink.aigateway.security.PasswordEncoderSupport;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionSecurityPropertiesValidator {

    /**
     * 历史上默认口令，出过文档也进过示例 yml。
     * <p>
     * 哈希之后没法"看一眼就知道是不是默认值"，但可以反过来验：把候选默认口令拿去 matches。
     */
    private static final List<String> DEFAULT_PASSWORDS = List.of("admin123456", "viewer123456");

    private final AiGatewayProperties properties;

    private final PasswordEncoderSupport passwordEncoderSupport;

    @PostConstruct
    public void validate() {
        validateJwtSecret();
        validateMasterKey();
        validateUserPassword("admin");
        validateUserPassword("viewer");
        validateNacosAddress();
    }

    /**
     * prod 必须配主密钥：缺了就等于"以为在加密、其实把 Key 明文写进了库"，
     * 而这件事在运行期没有任何症状，只能靠启动期拦住。
     */
    private void validateMasterKey() {
        String raw = properties.getSecurity().getSecret().getMasterKey();
        if (!StringUtils.hasText(raw)) {
            throw new IllegalStateException("prod 环境要求设置 AI_GATEWAY_MASTER_KEY（Base64 的 32 字节，生成：openssl rand -base64 32）");
        }
        try {
            MasterKey.fromBase64(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("prod 环境的 AI_GATEWAY_MASTER_KEY 不合法：" + ex.getMessage(), ex);
        }
    }

    private void validateJwtSecret() {
        String jwtSecret = properties.getSecurity().getJwtSecret();
        if (!StringUtils.hasText(jwtSecret) || jwtSecret.length() < 32
                || "replace-with-at-least-32-char-secret-key".equals(jwtSecret)) {
            throw new IllegalStateException("prod 环境要求设置有效的 AI_GATEWAY_JWT_SECRET，且长度至少 32 字符");
        }
    }

    private void validateUserPassword(String username) {
        AiGatewaySecurityProperties.UserCredential credential = properties.getSecurity().getUsers().get(username);
        String password = credential == null ? null : credential.getPassword();
        if (!StringUtils.hasText(password) || password.length() < 8) {
            throw new IllegalStateException("prod 环境要求为用户 " + username + " 设置长度至少 8 的密码");
        }
        // 明文口令会随配置快照、环境变量导出、审计日志一起泄漏，prod 一律要求哈希存储
        if (!passwordEncoderSupport.isEncoded(password)) {
            throw new IllegalStateException("prod 环境要求用户 " + username
                    + " 的密码以 {bcrypt} 开头存储（明文口令会随配置一起泄漏）");
        }
        for (String weak : DEFAULT_PASSWORDS) {
            if (passwordEncoderSupport.matches(password, weak)) {
                throw new IllegalStateException("prod 环境要求为用户 " + username + " 更换默认密码");
            }
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
