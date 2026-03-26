package com.nageoffer.shortlink.aigateway.security;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ConsoleAuthService {

    private final AiGatewayProperties properties;

    private final JwtTokenService jwtTokenService;

    public AuthPrincipal authenticate(HttpHeaders headers) {
        if (!properties.getSecurity().isEnabled()) {
            return new AuthPrincipal("anonymous", "admin");
        }
        String token = headers.getFirst("X-Console-Token");
        if (!StringUtils.hasText(token)) {
            throw new AiGatewayClientException(AiGatewayErrorCode.UNAUTHORIZED, "缺少 X-Console-Token");
        }
        JwtTokenService.JwtPrincipal principal = jwtTokenService.parse(token);
        return new AuthPrincipal(principal.username(), principal.role());
    }

    public void assertWriteAllowed(AuthPrincipal principal) {
        if (!properties.getSecurity().isEnabled()) {
            return;
        }
        if (!properties.getSecurity().getWriteRoles().contains(principal.role())) {
            throw new AiGatewayClientException(AiGatewayErrorCode.FORBIDDEN, "当前角色无写入权限");
        }
    }

    public LoginResult login(String username, String password) {
        AiGatewayProperties.UserCredential credential = properties.getSecurity().getUsers().get(username);
        if (credential == null || !StringUtils.hasText(credential.getPassword())) {
            throw new AiGatewayClientException(AiGatewayErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!credential.getPassword().equals(password)) {
            throw new AiGatewayClientException(AiGatewayErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        Instant expiresAt = Instant.now().plusSeconds(properties.getSecurity().getSessionTtlMinutes() * 60);
        String token = jwtTokenService.issue(username, credential.getRole());
        return new LoginResult(token, username, credential.getRole(), expiresAt);
    }

    public void logout(String token) {
        // JWT 无状态，客户端丢弃 token 即完成登出。
    }

    public record AuthPrincipal(String username, String role) {
    }

    public record LoginResult(String token, String username, String role, Instant expiresAt) {
    }
}
