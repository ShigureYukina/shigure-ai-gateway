package com.nageoffer.shortlink.aigateway.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 控制台口令的校验与哈希。
 * <p>
 * 存值格式是 Spring Security 惯用的 {@code {id}payload}：新哈希一律写成 {@code {bcrypt}$2a$...}。
 * 按前缀分流而不是"先试 bcrypt 再试明文"，是因为 BCrypt 对不带前缀的串只会返回 false 并打日志，
 * 混在一起会让"到底是明文还是坏哈希"无法区分。
 * <p>
 * <b>为什么还留着明文分支</b>：yml 里的口令是部署期由运维填的（`AI_GATEWAY_CONSOLE_...` 或直接明文），
 * 存量环境不可能一夜之间全换成哈希；直接删掉明文分支等于让所有存量环境登录失败。
 * prod 的正确收口在 {@code ProductionSecurityPropertiesValidator}（要求口令必须已是哈希），而不是在这里。
 */
@Component
public class PasswordEncoderSupport {

    private static final String BCRYPT_PREFIX = "{bcrypt}";

    private static final int BCRYPT_STRENGTH = 10;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    public String encode(String raw) {
        return BCRYPT_PREFIX + bcrypt.encode(raw);
    }

    /**
     * 是否已经是哈希。
     */
    public boolean isEncoded(String stored) {
        return StringUtils.hasText(stored) && stored.startsWith(BCRYPT_PREFIX);
    }

    /**
     * 校验。明文分支用常量时间比较，避免用 {@code String.equals} 泄漏长度与公共前缀。
     */
    public boolean matches(String stored, String raw) {
        if (!StringUtils.hasText(stored) || raw == null) {
            return false;
        }
        if (isEncoded(stored)) {
            return bcrypt.matches(raw, stored.substring(BCRYPT_PREFIX.length()));
        }
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), raw.getBytes(StandardCharsets.UTF_8));
    }
}
