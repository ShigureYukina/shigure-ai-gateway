package com.nageoffer.shortlink.aigateway.crypto;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * 密钥的确定性哈希，只用于"按明文 Key 查行"这一件事。
 * <p>
 * 为什么需要它：{@link AesGcmSecretCipher} 每次用随机 IV，同一个 Key 两次入库得到不同密文，
 * 于是 {@code tenant_api_key.api_key} 上的唯一约束与"按 Key 查行"同时失效。
 * 解法是把查找键换成 {@code api_key_hash}（HMAC-SHA256，64 位十六进制）：
 * 同一个明文永远得到同一个哈希，而拿到库的人没有主密钥就反推不出明文。
 * <p>
 * 用 HMAC 而不是裸 SHA-256：API Key 的熵有限且格式固定，裸 SHA-256 可以离线爆破，
 * 加了只有服务端知道的主密钥之后，拿走整张表也做不了字典攻击。
 * <p>
 * 没有主密钥时 {@link #enabled()} 为 false，调用方<b>必须</b>回退到明文查找
 * （此时 {@code api_key_hash} 列是 NULL，按哈希查会一行都查不到）。
 */
@Slf4j
public final class SecretHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    private SecretHasher(SecretKeySpec key) {
        this.key = key;
    }

    public static SecretHasher disabled() {
        return new SecretHasher(null);
    }

    public static SecretHasher of(MasterKey masterKey) {
        if (masterKey == null || !masterKey.present()) {
            return disabled();
        }
        return new SecretHasher(new SecretKeySpec(masterKey.bytes(), ALGORITHM));
    }

    public boolean enabled() {
        return key != null;
    }

    /**
     * @return 64 位小写十六进制；未配置主密钥时返回 null（调用方据此回退明文查找）
     */
    public String hmac(String plain) {
        if (!enabled() || plain == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("密钥哈希计算失败：" + ex.getMessage(), ex);
        }
    }
}
