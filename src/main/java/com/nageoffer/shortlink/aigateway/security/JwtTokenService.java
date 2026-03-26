package com.nageoffer.shortlink.aigateway.security;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JwtTokenService {

    private final AiGatewayProperties properties;

    public String issue(String username, String role) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.getSecurity().getSessionTtlMinutes() * 60);
        return Jwts.builder()
                .issuer(properties.getSecurity().getJwtIssuer())
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claims(Map.of("role", role))
                .signWith(signingKey())
                .compact();
    }

    public JwtPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .requireIssuer(properties.getSecurity().getJwtIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String username = claims.getSubject();
            String role = String.valueOf(claims.get("role"));
            Date expiration = claims.getExpiration();
            if (!StringUtils.hasText(username) || !StringUtils.hasText(role) || expiration == null) {
                throw new AiGatewayClientException(AiGatewayErrorCode.UNAUTHORIZED, "JWT内容不完整");
            }
            return new JwtPrincipal(username, role, expiration.toInstant());
        } catch (JwtException | IllegalArgumentException ex) {
            throw new AiGatewayClientException(AiGatewayErrorCode.UNAUTHORIZED, "JWT无效或已过期");
        }
    }

    private SecretKey signingKey() {
        String secret = properties.getSecurity().getJwtSecret();
        if (!StringUtils.hasText(secret) || secret.length() < 32) {
            throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, "jwtSecret长度至少32字符");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public record JwtPrincipal(String username, String role, Instant expiresAt) {
    }
}
