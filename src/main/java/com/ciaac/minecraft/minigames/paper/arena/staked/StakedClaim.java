package com.ciaac.minecraft.minigames.paper.arena.staked;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A pending or delivered claim over one exact participant payload. */
public record StakedClaim(UUID claimId, UUID escrowId, UUID sourcePlayerId, UUID beneficiaryId,
                          UUID resultId, StakedClaimKind kind, String payloadDigest,
                          StakedClaimState state, Optional<UUID> beginOperationId, Instant updatedAt) {
    public StakedClaim {
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(escrowId, "escrowId");
        Objects.requireNonNull(sourcePlayerId, "sourcePlayerId");
        Objects.requireNonNull(beneficiaryId, "beneficiaryId");
        Objects.requireNonNull(resultId, "resultId");
        Objects.requireNonNull(kind, "kind");
        payloadDigest = requireDigest(payloadDigest);
        Objects.requireNonNull(state, "state");
        beginOperationId = beginOperationId == null ? Optional.empty() : beginOperationId;
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String requireDigest(String value) {
        String normalized = Objects.requireNonNull(value, "payloadDigest").trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payloadDigest must be a SHA-256 hex digest");
        }
        return normalized;
    }
}
