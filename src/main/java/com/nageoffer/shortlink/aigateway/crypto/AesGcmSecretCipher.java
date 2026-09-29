package com.nageoffer.shortlink.aigateway.crypto;

import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 实现，输出 {@code enc:v1:} + Base64(IV ‖ 密文 ‖ tag)。
 * <p>
 * 选 GCM 而不是 CBC：GCM 自带完整性校验，"行被改了"会解密失败，而 CBC 会安静地解出一段垃圾。
 * 版本号写在值里（{@code v1}）是为了将来换算法时能按前缀分流，不必再动一次表。
 * <p>
 * 每次加密用新的 12 字节随机 IV：同一个明文两次加密得到不同密文。代价是密文不能当唯一键用 ——
 * 所以 {@code tenant_api_key} 走 {@link SecretHasher} 的确定性哈希做查找键，而不是用密文。
 */
@Slf4j
public final class AesGcmSecretCipher implements SecretCipher {

    /**
     * 加密后的值前缀，同时也是"这行是不是密文"的判据。
     */
    public static final String PREFIX = "enc:v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final String KEY_ALGORITHM = "AES";

    private static final int IV_BYTES = 12;

    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    private AesGcmSecretCipher(SecretKeySpec key) {
        this.key = key;
    }

    /**
     * 不加密的实现：{@code encrypt/decrypt} 原样透传。
     */
    public static AesGcmSecretCipher disabled() {
        return new AesGcmSecretCipher(null);
    }

    public static AesGcmSecretCipher of(MasterKey masterKey) {
        if (masterKey == null || !masterKey.present()) {
            return disabled();
        }
        return new AesGcmSecretCipher(new SecretKeySpec(masterKey.bytes(), KEY_ALGORITHM));
    }

    @Override
    public boolean enabled() {
        return key != null;
    }

    @Override
    public String encrypt(String plain) {
        if (!enabled() || plain == null || plain.isEmpty() || isEncrypted(plain)) {
            return plain;
        }
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText).array();
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            // 加密失败必须抛出：写进库的若是明文，等于静默把加密关掉
            throw new IllegalStateException("密钥加密失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public String decrypt(String stored) {
        if (!enabled() || stored == null || !isEncrypted(stored)) {
            return stored;
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException ex) {
            throw decryptFailed(ex);
        }
        if (payload.length <= IV_BYTES) {
            throw decryptFailed(null);
        }
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(payload, 0, iv, 0, IV_BYTES);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (AEADBadTagException ex) {
            throw decryptFailed(ex);
        } catch (GeneralSecurityException ex) {
            throw decryptFailed(ex);
        }
    }

    /**
     * 是否已经带加密前缀。判据只有前缀本身 —— 值是不是真的能解开，交给 {@link #decrypt(String)} 回答。
     */
    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    private AiGatewayClientException decryptFailed(Throwable cause) {
        // 不把 cause 的细节拼进消息：解密失败的信息对运维没有增量，但可能被用来试探密钥
        log.error("failed to decrypt secret with prefix {}, the row is corrupted or the master key was rotated", PREFIX);
        return new AiGatewayClientException(AiGatewayErrorCode.SECRET_DECRYPT_FAILED,
                "密钥解密失败：主密钥与库里的密文不匹配，或该行已损坏");
    }
}
