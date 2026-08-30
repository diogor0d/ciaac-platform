package com.ciaac.minecraft.minigames.paper.arena.staked;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only recovery evidence; no method on this type authorizes a retry. */
public record StakedRecoveryInspection(UUID escrowId, StakedEscrowState state, boolean blocked,
                                       String code, Optional<UUID> participantId,
                                       Optional<UUID> claimId, List<String> operationKinds) {
    public StakedRecoveryInspection {
        Objects.requireNonNull(escrowId, "escrowId");
        Objects.requireNonNull(state, "state");
        code = Objects.requireNonNull(code, "code").trim().toUpperCase(java.util.Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("code must be bounded");
        }
        participantId = participantId == null ? Optional.empty() : participantId;
        claimId = claimId == null ? Optional.empty() : claimId;
        operationKinds = List.copyOf(Objects.requireNonNull(operationKinds, "operationKinds"));
    }
}
