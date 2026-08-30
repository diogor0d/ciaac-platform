package com.ciaac.minecraft.minigames.paper.arena.staked;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded outcome shared by every durable escrow operation. */
public record StakedOperationResult(UUID operationId, UUID escrowId, Optional<UUID> subjectId,
                                    StakedEscrowState state, boolean accepted, boolean idempotent,
                                    String code) {
    public StakedOperationResult {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(escrowId, "escrowId");
        subjectId = subjectId == null ? Optional.empty() : subjectId;
        Objects.requireNonNull(state, "state");
        code = normalizeCode(code, "code");
    }

    public static StakedOperationResult accepted(UUID operationId, UUID escrowId, UUID subjectId,
                                                 StakedEscrowState state, String code) {
        return new StakedOperationResult(operationId, escrowId, Optional.ofNullable(subjectId),
                state, true, false, code);
    }

    public static StakedOperationResult rejected(UUID operationId, UUID escrowId, UUID subjectId,
                                                 StakedEscrowState state, String code) {
        return new StakedOperationResult(operationId, escrowId, Optional.ofNullable(subjectId),
                state, false, false, code);
    }

    public StakedOperationResult asIdempotentReplay() {
        return new StakedOperationResult(operationId, escrowId, subjectId, state, accepted, true, code);
    }

    private static String normalizeCode(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException(name + " must be a bounded machine-readable code");
        }
        return normalized;
    }
}
