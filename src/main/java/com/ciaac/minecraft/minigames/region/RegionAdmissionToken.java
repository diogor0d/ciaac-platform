package com.ciaac.minecraft.minigames.region;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RegionAdmissionToken(
        UUID tokenId,
        UUID sessionId,
        UUID playerId,
        String regionId,
        Instant issuedAt,
        Instant expiresAt) {

    public RegionAdmissionToken {
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(playerId, "playerId");
        if (regionId == null || !regionId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("regionId is invalid");
        }
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("Region token must expire after issuance");
        }
    }

    public boolean validAt(Instant now) {
        return !now.isBefore(issuedAt) && now.isBefore(expiresAt);
    }
}
