package com.ciaac.minecraft.minigames.arena;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Capability issued by the authenticated adapter for one player in one match. */
public record ArenaAdmissionToken(
        UUID tokenId,
        UUID matchId,
        UUID playerId,
        Instant issuedAt,
        Instant expiresAt) {
    public ArenaAdmissionToken {
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("Admission token must expire after issuance");
        }
    }

    public boolean isValidAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(issuedAt) && now.isBefore(expiresAt);
    }
}
