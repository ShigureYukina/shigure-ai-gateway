package com.nageoffer.shortlink.aigateway.security;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;

class SessionRegistryTest {

    @Test
    void shouldRegisterFindAndRevokeSession() {
        SessionRegistry registry = new SessionRegistry();
        SessionRegistry.SessionInfo session = new SessionRegistry.SessionInfo(
                "t1", "alice", "admin", Instant.now().plusSeconds(60)
        );

        registry.register(session);
        SessionRegistry.SessionInfo found = registry.find("t1").orElseThrow();
        Assertions.assertEquals("alice", found.username());

        registry.revoke("t1");
        Assertions.assertTrue(registry.find("t1").isEmpty());
    }

    @Test
    void shouldReturnEmptyForExpiredSession() {
        SessionRegistry registry = new SessionRegistry();
        registry.register(new SessionRegistry.SessionInfo(
                "t-expired", "bob", "viewer", Instant.now().minusSeconds(5)
        ));

        Assertions.assertTrue(registry.find("t-expired").isEmpty());
        Assertions.assertTrue(registry.find("t-expired").isEmpty());
    }
}
