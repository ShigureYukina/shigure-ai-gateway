package com.nageoffer.shortlink.aigateway.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制台安全域属性组：对应 {@code short-link.ai-gateway.security.*}。
 * <p>
 * 绑定入口仍是 {@link AiGatewayProperties}——本类不注册为独立配置 Bean，
 * 只做属性分组，因此 yml 键与注入点都不变。
 */
@Data
public class AiGatewaySecurityProperties {

    /**
     * 是否启用控制台鉴权。
     * <p>
     * 默认开启（安全默认值）：关掉之后 62 个管理端点（含写上游 Key、删租户）全部匿名可达。
     * 注意这个 Java 默认值**单独改是无效的** —— {@code application.yml} 里显式配了同名键，
     * 绑定时 yml 优先；本地要用开关关闭请设环境变量 {@code AI_GATEWAY_SECURITY_ENABLED=false}。
     */
    private boolean enabled = true;

    /**
     * 登录会话有效期（分钟）
     */
    private Long sessionTtlMinutes = 120L;

    /**
     * JWT 签名密钥（HS256，建议32字节以上）
     */
    private String jwtSecret = "replace-with-at-least-32-char-secret-key";

    /**
     * JWT issuer
     */
    private String jwtIssuer = "ai-gateway-console";

    /**
     * 用户列表（用户名 -> 凭证）
     */
    private Map<String, UserCredential> users = new HashMap<>(Map.of(
            "admin", new UserCredential("admin123456", "admin"),
            "viewer", new UserCredential("viewer123456", "viewer")
    ));

    /**
     * 写操作允许的角色
     */
    private List<String> writeRoles = new ArrayList<>(List.of("admin"));

    private Secret secret = new Secret();

    private Ssrf ssrf = new Ssrf();

    /**
     * 密钥加密：Master Key 是整个加密体系的唯一信任根。
     */
    @Data
    public static class Secret {

        /**
         * Base64 编码的 32 字节主密钥，来自 {@code AI_GATEWAY_MASTER_KEY}。
         * <p>
         * 空值 = 不加密（dev 的合法状态：{@code encrypt/decrypt} 原样透传，永远不会产生解不开的数据）。
         * 配了但不合法（非 Base64 / 长度不对）会<b>直接启动失败</b>，绝不静默降级成明文。
         * prod 由 {@code ProductionSecurityPropertiesValidator} 强制要求非空。
         */
        private String masterKey;
    }

    /**
     * 出站地址（SSRF）校验。
     */
    @Data
    public static class Ssrf {

        /**
         * 是否放行私网与回环地址。
         * <p>
         * 本地把上游指向 {@code http://localhost:11434}（Ollama）是正常用法，所以 dev 默认放行；
         * prod 的 yml 硬编码 {@code false}。无论开关怎么设，云元数据主机名
         * （{@code metadata.google.internal} 等）、{@code userinfo}、非 http(s) 一律拒。
         */
        private boolean allowPrivateAddresses = true;
    }

    @Data
    public static class UserCredential {

        private String password;

        private String role;

        public UserCredential() {
        }

        public UserCredential(String password, String role) {
            this.password = password;
            this.role = role;
        }
    }
}
