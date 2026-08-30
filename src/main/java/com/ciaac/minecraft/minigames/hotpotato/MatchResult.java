package com.ciaac.minecraft.minigames.hotpotato;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable, match-scoped result. */
public record MatchResult(
        UUID matchId,
        UUID winner,
        Set<UUID> participants,
        Instant finishedAt) {

    public MatchResult {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(winner, "winner");
        Objects.requireNonNull(participants, "participants");
        Objects.requireNonNull(finishedAt, "finishedAt");
        participants = Set.copyOf(participants);
        if (!participants.contains(winner)) {
            throw new IllegalArgumentException("winner must be a participant");
        }
    }
}
