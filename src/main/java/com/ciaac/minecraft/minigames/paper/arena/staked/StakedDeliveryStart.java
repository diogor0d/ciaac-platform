package com.ciaac.minecraft.minigames.paper.arena.staked;

import java.util.Objects;
import java.util.Optional;

/** Durable delivery reservation plus the exact bytes the live adapter may apply. */
public record StakedDeliveryStart(StakedOperationResult outcome, Optional<StakedClaim> claim,
                                  Optional<StakedInventoryPayload> payload) {
    public StakedDeliveryStart {
        Objects.requireNonNull(outcome, "outcome");
        claim = claim == null ? Optional.empty() : claim;
        payload = payload == null ? Optional.empty() : payload;
        if (outcome.accepted() && (claim.isEmpty() || payload.isEmpty())) {
            throw new IllegalArgumentException("Accepted delivery must carry its claim and exact payload");
        }
    }
}
