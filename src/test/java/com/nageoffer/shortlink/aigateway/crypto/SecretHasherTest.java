package com.nageoffer.shortlink.aigateway.crypto;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Base64;

class SecretHasherTest {

    private static final MasterKey KEY = masterKey((byte) 3);

    private final SecretHasher hasher = SecretHasher.of(KEY);

    @Test
    void shouldBeDeterministic() {
        String first = hasher.hmac("sk-tenant-abc");
        String second = hasher.hmac("sk-tenant-abc");

        Assertions.assertEquals(first, second, "查找索引必须确定性，否则同一个 Key 查不到自己那行");
        Assertions.assertEquals(64, first.length(), "CHAR(64) 列，必须是 64 位十六进制");
        Assertions.assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    void shouldDifferPerPlaintextAndPerMasterKey() {
        Assertions.assertNotEquals(hasher.hmac("sk-a"), hasher.hmac("sk-b"));
        Assertions.assertNotEquals(hasher.hmac("sk-a"), SecretHasher.of(masterKey((byte) 4)).hmac("sk-a"),
                "换主密钥后旧哈希失效，这就是轮换主密钥必须重算索引的原因");
    }

    @Test
    void shouldReturnNullWhenMasterKeyIsAbsent() {
        SecretHasher disabled = SecretHasher.of(MasterKey.absent());

        Assertions.assertFalse(disabled.enabled());
        Assertions.assertNull(disabled.hmac("sk-tenant-abc"), "调用方看到 null 就知道要回退明文查找");
        Assertions.assertNull(disabled.hmac(null));
    }

    @Test
    void shouldNotEchoThePlaintext() {
        Assertions.assertFalse(hasher.hmac("sk-tenant-abc").contains("sk-tenant"));
    }

    private static MasterKey masterKey(byte fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        return MasterKey.fromBase64(Base64.getEncoder().encodeToString(bytes));
    }
}
