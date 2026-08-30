package com.ciaac.minecraft.minigames.hotpotato;

import java.util.Objects;
import java.util.UUID;

/**
 * Idempotency key for a state mutation. The sequence is scoped to a match and
 * lets the domain reject delayed events as well as exact duplicates.
 */
public record OperationId(UUID matchId, long sequence) {
    public OperationId {
        Objects.requireNonNull(matchId, "matchId");
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }
}
