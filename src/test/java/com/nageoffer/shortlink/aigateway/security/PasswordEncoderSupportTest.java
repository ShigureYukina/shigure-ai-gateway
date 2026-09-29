package com.nageoffer.shortlink.aigateway.security;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PasswordEncoderSupportTest {

    private final PasswordEncoderSupport support = new PasswordEncoderSupport();

    @Test
    void shouldRoundTripEncodedPassword() {
        String encoded = support.encode("p@ssw0rd-strong");

        Assertions.assertTrue(encoded.startsWith("{bcrypt}$2"), "存值要带算法前缀：" + encoded);
        Assertions.assertTrue(support.isEncoded(encoded));
        Assertions.assertTrue(support.matches(encoded, "p@ssw0rd-strong"));
        Assertions.assertFalse(support.matches(encoded, "p@ssw0rd-stronG"));
    }

    @Test
    void shouldUseRandomSaltSoTwoEncodesOfTheSamePasswordDiffer() {
        String first = support.encode("same-password");
        String second = support.encode("same-password");

        Assertions.assertNotEquals(first, second, "bcrypt 每次加随机盐，否则能靠密文相等反查相同口令");
        Assertions.assertTrue(support.matches(first, "same-password"));
        Assertions.assertTrue(support.matches(second, "same-password"));
    }

    @Test
    void shouldStillAcceptLegacyPlaintextPassword() {
        // 存量 yml 里的明文口令不能一夜之间失效，prod 的收口在 ProductionSecurityPropertiesValidator
        Assertions.assertFalse(support.isEncoded("admin123456"));
        Assertions.assertTrue(support.matches("admin123456", "admin123456"));
        Assertions.assertFalse(support.matches("admin123456", "admin1234567"));
    }

    @Test
    void shouldRejectBlankAndNullInputs() {
        Assertions.assertFalse(support.matches(null, "any"));
        Assertions.assertFalse(support.matches("", "any"));
        Assertions.assertFalse(support.matches("   ", "any"));
        Assertions.assertFalse(support.matches("admin123456", null));
        Assertions.assertFalse(support.isEncoded(null));
        Assertions.assertFalse(support.isEncoded(""));
    }

    @Test
    void shouldNotTreatBrokenBcryptValueAsPlaintext() {
        // 前缀对但载荷坏掉时必须返回 false，而不是"不等就走明文分支"
        String broken = "{bcrypt}not-a-real-hash";

        Assertions.assertTrue(support.isEncoded(broken));
        Assertions.assertFalse(support.matches(broken, "not-a-real-hash"));
    }
}
