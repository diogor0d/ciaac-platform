package com.ciaac.minecraft.minigames.retention;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reward state is durable and idempotent; uncertain external effects are never retried automatically. */
public record Entitlement(String seasonId, String rewardId, EntitlementState state, Instant earnedAt,
                          Instant updatedAt, UUID operationId, String detailCode) {
    public Entitlement {
        if (seasonId == null || seasonId.isBlank() || rewardId == null || rewardId.isBlank()) throw new IllegalArgumentException("entitlement key is invalid");
        state = Objects.requireNonNull(state, "state");
        earnedAt = Objects.requireNonNull(earnedAt, "earnedAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        detailCode = Objects.requireNonNull(detailCode, "detailCode");
    }
    public String key() { return seasonId + ":" + rewardId; }
    public Optional<UUID> operation() { return Optional.ofNullable(operationId); }

    public enum EntitlementState { EARNED, GRANTING, GRANTED, DELIVERY_PENDING, MANUAL_REVIEW }
}
