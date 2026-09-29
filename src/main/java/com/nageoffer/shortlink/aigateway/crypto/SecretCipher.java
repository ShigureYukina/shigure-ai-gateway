package com.nageoffer.shortlink.aigateway.crypto;

/**
 * 可逆密钥加解密。
 * <p>
 * 调用方必须按"可能没开加密"来写：{@link #enabled()} 为 false 时 {@code encrypt/decrypt} 都是原样透传，
 * 这样 dev（不配主密钥）与 prod（必须配主密钥）走同一条代码路径，不用为两种模式各写一套。
 * <p>
 * 存量兼容靠 {@code decrypt} 的判定顺序，见 {@link AesGcmSecretCipher#decrypt(String)}。
 */
public interface SecretCipher {

    /**
     * 是否真的在做加解密。false 时两个方法都不改变入参。
     */
    boolean enabled();

    /**
     * 加密；已加密的值不该再传进来（调用方用 {@link #enabled()} 或前缀自行判断）。
     */
    String encrypt(String plain);

    /**
     * 解密。历史明文值原样返回；声明了加密前缀却解不开时抛 {@code SECRET_DECRYPT_FAILED}。
     */
    String decrypt(String stored);
}
