package com.ciaac.minecraft.minigames.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Connection-bound authentication capabilities.
 *
 * <p>PlayerJoinEvent must never populate this registry. Only the separately
 * validated nLogin adapter may issue a capability.</p>
 */
public final class AuthenticationRegistry {
    private final Map<UUID, AuthenticatedSession> byPlayer = new LinkedHashMap<>();

    public synchronized void authenticated(AuthenticatedSession session) {
        Objects.requireNonNull(session, "session");
        AuthenticatedSession previous = byPlayer.get(session.playerId());
        if (previous != null && previous.authenticatedAt().isAfter(session.authenticatedAt())) {
            throw new IllegalStateException("Cannot replace newer authentication evidence");
        }
        byPlayer.put(session.playerId(), session);
    }

    public synchronized boolean invalidate(UUID playerId, UUID connectionId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(connectionId, "connectionId");
        AuthenticatedSession current = byPlayer.get(playerId);
        if (current == null || !current.connectionId().equals(connectionId)) {
            return false;
        }
        byPlayer.remove(playerId);
        return true;
    }

    public synchronized boolean invalidatePlayer(UUID playerId) {
        return byPlayer.remove(Objects.requireNonNull(playerId, "playerId")) != null;
    }

    public synchronized Optional<AuthenticatedSession> current(UUID playerId, Instant now) {
        AuthenticatedSession current = byPlayer.get(Objects.requireNonNull(playerId, "playerId"));
        if (current == null || !current.validAt(Objects.requireNonNull(now, "now"))) {
            return Optional.empty();
        }
        return Optional.of(current);
    }
}
