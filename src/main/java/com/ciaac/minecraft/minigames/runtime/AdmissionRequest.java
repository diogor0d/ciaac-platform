package com.ciaac.minecraft.minigames.runtime;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Fully identified request; callers must persist/reuse IDs when retrying. */
public record AdmissionRequest(
        UUID requestId,
        UUID sessionId,
        UUID snapshotId,
        UUID matchId,
        UUID playerId,
        UUID connectionId,
        GameKey game,
        Instant requestedAt) {

    public AdmissionRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
