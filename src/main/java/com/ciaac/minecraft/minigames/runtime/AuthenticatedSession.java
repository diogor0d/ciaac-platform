package com.ciaac.minecraft.minigames.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Capability emitted only after validated authentication and provider state completion. */
public record AuthenticatedSession(
        UUID capabilityId,
        UUID playerId,
        UUID connectionId,
        Instant authenticatedAt,
        Instant expiresAt) {

    public AuthenticatedSession {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(authenticatedAt)) {
            throw new IllegalArgumentException("Authentication capability must expire after issuance");
        }
    }

    public boolean validAt(Instant now) {
        return !Objects.requireNonNull(now, "now").isBefore(authenticatedAt)
                && now.isBefore(expiresAt);
    }
}
