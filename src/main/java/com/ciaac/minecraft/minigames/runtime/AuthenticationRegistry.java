package com.ciaac.minecraft.minigames.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Connection-bound authentication capabilities.
 *
 * <p>PlayerJoinEvent must never populate this registry. Only the separately
 * validated adapter may issue a capability after authentication and completion
 * of the provider's player-state restore.</p>
 */
public final class AuthenticationRegistry {
    private record Evidence(AuthenticatedSession session, BooleanSupplier stillValid) {}
    private final Map<UUID, Evidence> byPlayer = new LinkedHashMap<>();

    public synchronized void authenticated(AuthenticatedSession session) {
        authenticated(session, () -> true);
    }

    /** A provider lease is rechecked on every use; losing it permanently revokes this capability. */
    public synchronized void authenticated(AuthenticatedSession session, BooleanSupplier stillValid) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(stillValid, "stillValid");
        Evidence previous = byPlayer.get(session.playerId());
        if (previous != null && previous.session().authenticatedAt().isAfter(session.authenticatedAt())) {
            throw new IllegalStateException("Cannot replace newer authentication evidence");
        }
        byPlayer.put(session.playerId(), new Evidence(session, stillValid));
    }

    public synchronized boolean invalidate(UUID playerId, UUID connectionId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(connectionId, "connectionId");
        Evidence current = byPlayer.get(playerId);
        if (current == null || !current.session().connectionId().equals(connectionId)) {
            return false;
        }
        byPlayer.remove(playerId);
        return true;
    }

    public synchronized boolean invalidatePlayer(UUID playerId) {
        return byPlayer.remove(Objects.requireNonNull(playerId, "playerId")) != null;
    }

    public synchronized Optional<AuthenticatedSession> current(UUID playerId, Instant now) {
        Objects.requireNonNull(now, "now");
        Evidence current = byPlayer.get(Objects.requireNonNull(playerId, "playerId"));
        if (current == null) return Optional.empty();
        boolean valid;
        try {
            valid = current.session().validAt(now) && current.stillValid().getAsBoolean();
        } catch (RuntimeException | LinkageError failure) {
            valid = false;
        }
        if (!valid) {
            byPlayer.remove(playerId);
            return Optional.empty();
        }
        return Optional.of(current.session());
    }
}
