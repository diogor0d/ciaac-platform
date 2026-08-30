package com.ciaac.minecraft.minigames.arena;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable evidence reference for one player's snapshot or restore operation. */
public record PlayerStateOperation(UUID playerId, UUID operationId, Instant observedAt) {
    public PlayerStateOperation {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
