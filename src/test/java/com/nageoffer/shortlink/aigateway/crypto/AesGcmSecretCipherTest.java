package com.nageoffer.shortlink.aigateway.crypto;

import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Base64;

class AesGcmSecretCipherTest {

    private static final MasterKey KEY = masterKey((byte) 7);

    private final SecretCipher cipher = AesGcmSecretCipher.of(KEY);

    @Test
    void shouldRoundTripSecret() {
        String plain = "sk-proj-abcdefghijklmnopqrstuvwxyz-1234567890";

        String encrypted = cipher.encrypt(plain);

        Assertions.assertTrue(encrypted.startsWith(AesGcmSecretCipher.PREFIX));
        Assertions.assertNotEquals(plain, encrypted);
        Assertions.assertEquals(plain, cipher.decrypt(encrypted));
    }

    @Test
    void shouldUseRandomIvSoCipherTextIsNotStable() {
        String first = cipher.encrypt("same-secret");
        String second = cipher.encrypt("same-secret");

        Assertions.assertNotEquals(first, second, "随机 IV 是刻意的：否则密文相等会泄漏'两个租户用了同一个 Key'");
        Assertions.assertEquals("same-secret", cipher.decrypt(first));
        Assertions.assertEquals("same-secret", cipher.decrypt(second));
    }

    @Test
    void shouldDetectTamperedCipherText() {
        String encrypted = cipher.encrypt("sk-secret");
        byte[] raw = Base64.getDecoder().decode(encrypted.substring(AesGcmSecretCipher.PREFIX.length()));
        raw[raw.length - 1] ^= 0x01;
        String tampered = AesGcmSecretCipher.PREFIX + Base64.getEncoder().encodeToString(raw);

        AiGatewayClientException ex = Assertions.assertThrows(AiGatewayClientException.class,
                () -> cipher.decrypt(tampered));

        // GCM 的价值就在这里：改一位就解不开，而不是安静地吐出一段垃圾
        Assertions.assertEquals(AiGatewayErrorCode.SECRET_DECRYPT_FAILED, ex.getErrorCode());
        Assertions.assertEquals(500, ex.getErrorCode().getStatus());
    }

    @Test
    void shouldRejectValueEncryptedWithAnotherMasterKey() {
        String encrypted = cipher.encrypt("sk-secret");

        AiGatewayClientException ex = Assertions.assertThrows(AiGatewayClientException.class,
                () -> AesGcmSecretCipher.of(masterKey((byte) 9)).decrypt(encrypted));

        Assertions.assertEquals(AiGatewayErrorCode.SECRET_DECRYPT_FAILED, ex.getErrorCode());
    }

    @Test
    void shouldTreatLegacyPlaintextAsPlaintext() {
        Assertions.assertEquals("sk-legacy-plaintext", cipher.decrypt("sk-legacy-plaintext"));
        Assertions.assertFalse(AesGcmSecretCipher.isEncrypted("sk-legacy-plaintext"));
    }

    @Test
    void shouldThrowOnBrokenEncryptedPayloadInsteadOfReturningItAsPlaintext() {
        // 前缀对但载荷坏掉：绝不能当明文返回，否则会变成"古怪的 apiKey" + 上游 401
        AiGatewayClientException badBase64 = Assertions.assertThrows(AiGatewayClientException.class,
                () -> cipher.decrypt(AesGcmSecretCipher.PREFIX + "!!!not-base64!!!"));
        Assertions.assertEquals(AiGatewayErrorCode.SECRET_DECRYPT_FAILED, badBase64.getErrorCode());

        AiGatewayClientException tooShort = Assertions.assertThrows(AiGatewayClientException.class,
                () -> cipher.decrypt(AesGcmSecretCipher.PREFIX + Base64.getEncoder().encodeToString(new byte[4])));
        Assertions.assertEquals(AiGatewayErrorCode.SECRET_DECRYPT_FAILED, tooShort.getErrorCode());
    }

    @Test
    void shouldPassThroughWhenMasterKeyIsAbsent() {
        SecretCipher disabled = AesGcmSecretCipher.of(MasterKey.absent());

        Assertions.assertFalse(disabled.enabled());
        Assertions.assertEquals("sk-plain", disabled.encrypt("sk-plain"));
        Assertions.assertEquals("sk-plain", disabled.decrypt("sk-plain"));
        // 没有主密钥时不认前缀，避免"半加密"状态下的误判
        Assertions.assertEquals(AesGcmSecretCipher.PREFIX + "abc", disabled.decrypt(AesGcmSecretCipher.PREFIX + "abc"));
    }

    @Test
    void shouldLeaveNullOrEmptyUntouched() {
        Assertions.assertNull(cipher.encrypt(null));
        Assertions.assertEquals("", cipher.encrypt(""));
        Assertions.assertNull(cipher.decrypt(null));
        Assertions.assertEquals("", cipher.decrypt(""));
    }

    @Test
    void shouldNotDoubleEncryptAnAlreadyEncryptedValue() {
        String once = cipher.encrypt("sk-secret");

        Assertions.assertEquals(once, cipher.encrypt(once));
        Assertions.assertEquals("sk-secret", cipher.decrypt(cipher.encrypt(once)));
    }

    @Test
    void shouldRejectMalformedMasterKeyAtConstruction() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> MasterKey.fromBase64("not base64!!"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MasterKey.fromBase64(Base64.getEncoder().encodeToString(new byte[16])));
        Assertions.assertFalse(MasterKey.fromBase64("  ").present());
    }

    private static MasterKey masterKey(byte fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        return MasterKey.fromBase64(Base64.getEncoder().encodeToString(bytes));
    }
}
