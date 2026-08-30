package com.ciaac.minecraft.minigames.arena;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Fresh consent over the exact immutable manifest for one staked match. */
public record StakedConsent(UUID playerId, UUID matchId, String rulesetDigest,
                            String manifestDigest, Instant consentedAt) {
    public StakedConsent {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(matchId, "matchId");
        if (Objects.requireNonNull(rulesetDigest, "rulesetDigest").isBlank()) {
            throw new IllegalArgumentException("rulesetDigest cannot be blank");
        }
        if (Objects.requireNonNull(manifestDigest, "manifestDigest").isBlank()) {
            throw new IllegalArgumentException("manifestDigest cannot be blank");
        }
        Objects.requireNonNull(consentedAt, "consentedAt");
    }
}
