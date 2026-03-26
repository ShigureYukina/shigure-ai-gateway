package com.nageoffer.shortlink.aigateway.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRegistry {

    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    public void register(SessionInfo sessionInfo) {
        sessions.put(sessionInfo.token(), sessionInfo);
    }

    public Optional<SessionInfo> find(String token) {
        SessionInfo info = sessions.get(token);
        if (info == null) {
            return Optional.empty();
        }
        if (info.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(info);
    }

    public void revoke(String token) {
        sessions.remove(token);
    }

    public record SessionInfo(String token, String username, String role, Instant expiresAt) {
    }
}
