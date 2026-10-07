package com.ciaac.minecraft.minigames.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AuthenticationRegistryLeaseTest {
    private static final Instant NOW = Instant.parse("2026-10-05T18:00:00Z");

    @Test void losingProviderEvidencePermanentlyRevokesExistingCapability() {
        AuthenticationRegistry registry = new AuthenticationRegistry();
        AuthenticatedSession session = session();
        AtomicBoolean valid = new AtomicBoolean(true);
        registry.authenticated(session, valid::get);
        assertEquals(session, registry.current(session.playerId(), NOW).orElseThrow());
        valid.set(false);
        assertTrue(registry.current(session.playerId(), NOW).isEmpty());
        valid.set(true);
        assertTrue(registry.current(session.playerId(), NOW).isEmpty());
    }

    @Test void providerExceptionsAndExpiredEvidenceFailClosed() {
        AuthenticationRegistry registry = new AuthenticationRegistry();
        AuthenticatedSession session = session();
        registry.authenticated(session, () -> { throw new IllegalStateException("synthetic"); });
        assertTrue(registry.current(session.playerId(), NOW).isEmpty());
        registry.authenticated(session, () -> { throw new LinkageError("synthetic"); });
        assertTrue(registry.current(session.playerId(), NOW).isEmpty());
        registry.authenticated(session);
        assertTrue(registry.current(session.playerId(), NOW.plusSeconds(60)).isEmpty());
        assertTrue(registry.current(session.playerId(), NOW).isEmpty());
    }

    @Test void staleLogoutCannotRevokeTheReplacementConnection() {
        AuthenticationRegistry registry = new AuthenticationRegistry();
        AuthenticatedSession session = session();
        registry.authenticated(session);
        assertFalse(registry.invalidate(session.playerId(), UUID.randomUUID()));
        assertTrue(registry.current(session.playerId(), NOW).isPresent());
        assertTrue(registry.invalidate(session.playerId(), session.connectionId()));
        assertTrue(registry.current(session.playerId(), NOW).isEmpty());
    }

    private static AuthenticatedSession session() {
        return new AuthenticatedSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW, NOW.plusSeconds(60));
    }
}
