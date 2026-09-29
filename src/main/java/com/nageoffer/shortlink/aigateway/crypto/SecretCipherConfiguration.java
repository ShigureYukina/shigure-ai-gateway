package com.nageoffer.shortlink.aigateway.crypto;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 密钥加密的装配。
 * <p>
 * 三个 Bean 都从同一个 {@link MasterKey} 派生，保证"加密用的密钥"与"哈希查找用的密钥"必然一致。
 * 主密钥非法时这里就抛（构造期失败），而不是退化成明文 —— 见 {@link MasterKey#fromBase64(String)}。
 */
@Slf4j
@Configuration
public class SecretCipherConfiguration {

    @Bean
    public MasterKey masterKey(AiGatewayProperties properties) {
        MasterKey masterKey = MasterKey.fromBase64(properties.getSecurity().getSecret().getMasterKey());
        if (!masterKey.present()) {
            log.warn("AI_GATEWAY_MASTER_KEY is not configured: secret encryption and API key hashing are DISABLED, "
                    + "values are stored as plaintext. This is fine for local development; "
                    + "prod refuses to start without a master key (see ProductionSecurityPropertiesValidator).");
        }
        return masterKey;
    }

    @Bean
    public SecretCipher secretCipher(MasterKey masterKey) {
        return AesGcmSecretCipher.of(masterKey);
    }

    @Bean
    public SecretHasher secretHasher(MasterKey masterKey) {
        return SecretHasher.of(masterKey);
    }
}
