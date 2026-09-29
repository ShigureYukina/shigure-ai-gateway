package com.nageoffer.shortlink.aigateway.crypto;

import java.util.Base64;

/**
 * 主密钥（`AI_GATEWAY_MASTER_KEY`，Base64 编码的 32 字节）。
 * <p>
 * 单独抽一个值类型，是为了让 {@link AesGcmSecretCipher} 与 {@link SecretHasher} <b>共用同一把密钥</b>：
 * 两边各自去解析一遍配置很容易在"一边判空、一边判非空"上分叉，而那种分叉的后果是
 * 加密开着、哈希查找关着，表现为"写进去的 Key 查不出来"。
 * <p>
 * 三种状态分得很清：
 * <ul>
 *   <li>未配置（null/空串）→ {@link #present()} 为 false，调用方退化为不加密；</li>
 *   <li>配置了但不合法（非 Base64 / 解码后不是 32 字节）→ <b>构造即抛</b>，绝不静默降级。
 *       静默降级会让"以为加密了"的环境把明文写进库；</li>
 *   <li>合法 → {@link #bytes()} 返回 32 字节。</li>
 * </ul>
 */
public final class MasterKey {

    private static final int KEY_BYTES = 32;

    private static final MasterKey ABSENT = new MasterKey(null);

    private final byte[] keyBytes;

    private MasterKey(byte[] keyBytes) {
        this.keyBytes = keyBytes;
    }

    public static MasterKey absent() {
        return ABSENT;
    }

    /**
     * @param base64 Base64 编码的 32 字节主密钥；空值表示"不配置"，返回 {@link #absent()}
     * @throws IllegalArgumentException Base64 非法或解码后长度不为 32 字节
     */
    public static MasterKey fromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            return ABSENT;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("AI_GATEWAY_MASTER_KEY 不是合法的 Base64：（" + ex.getMessage() + "）", ex);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalArgumentException("AI_GATEWAY_MASTER_KEY 解码后必须是 " + KEY_BYTES
                    + " 字节（AES-256），当前是 " + decoded.length + " 字节");
        }
        return new MasterKey(decoded);
    }

    public boolean present() {
        return keyBytes != null;
    }

    /**
     * @return 32 字节原始密钥的副本；未配置时为 null
     */
    public byte[] bytes() {
        return keyBytes == null ? null : keyBytes.clone();
    }
}
