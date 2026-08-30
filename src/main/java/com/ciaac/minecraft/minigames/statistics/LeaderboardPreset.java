package com.ciaac.minecraft.minigames.statistics;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.Objects;

/** Default public projection for one game; raw evidence remains immutable. */
public record LeaderboardPreset(
        GameKey game,
        String metric,
        String labelPtPt,
        MetricAggregation aggregation,
        LeaderboardDirection direction) {
    public LeaderboardPreset {
        Objects.requireNonNull(game, "game");
        if (metric == null || !metric.matches("[a-z0-9][a-z0-9_.-]{0,31}")) {
            throw new IllegalArgumentException("Invalid metric");
        }
        if (labelPtPt == null || labelPtPt.isBlank()) throw new IllegalArgumentException("Missing label");
        Objects.requireNonNull(aggregation, "aggregation");
        Objects.requireNonNull(direction, "direction");
    }

    public LeaderboardQuery query(LeaderboardScope scope, int limit) {
        Objects.requireNonNull(scope, "scope");
        if (scope.game() != game || !scope.metric().equals(metric)) {
            throw new IllegalArgumentException("Leaderboard scope does not match preset");
        }
        return new LeaderboardQuery(
                game, scope.ruleset(), scope.mode(), metric,
                aggregation, direction, LeaderboardPeriod.allTime(), 1, limit);
    }
}
