package com.ciaac.minecraft.minigames.statistics;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** One immutable row in a projected leaderboard. */
public record LeaderboardEntry(
        int rank,
        UUID playerId,
        BigDecimal value,
        long sampleCount) {

    public LeaderboardEntry {
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be positive");
        }
        Objects.requireNonNull(playerId, "playerId");
        value = Objects.requireNonNull(value, "value").stripTrailingZeros();
        if (sampleCount < 1) {
            throw new IllegalArgumentException("sampleCount must be positive");
        }
    }
}
