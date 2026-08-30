package com.ciaac.minecraft.minigames.statistics;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.Objects;
import java.util.Optional;

/** Immutable, exact-scope query for a deterministic leaderboard projection. */
public record LeaderboardQuery(
        GameKey game,
        Optional<String> ruleset,
        Optional<String> mode,
        String metric,
        MetricAggregation aggregation,
        LeaderboardDirection direction,
        LeaderboardPeriod period,
        int minimumSamples,
        int limit) {

    public LeaderboardQuery {
        game = Objects.requireNonNull(game, "game");
        ruleset = Objects.requireNonNull(ruleset, "ruleset")
                .map(value -> StatisticsIdentifiers.identifier(value, "ruleset"));
        mode = Objects.requireNonNull(mode, "mode")
                .map(value -> StatisticsIdentifiers.identifier(value, "mode"));
        metric = StatisticsIdentifiers.metricKey(metric);
        aggregation = Objects.requireNonNull(aggregation, "aggregation");
        direction = Objects.requireNonNull(direction, "direction");
        period = Objects.requireNonNull(period, "period");
        if (minimumSamples < 1) {
            throw new IllegalArgumentException("minimumSamples must be at least one");
        }
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be between one and 1000");
        }
    }

    public LeaderboardQuery(GameKey game, String metric) {
        this(game, Optional.empty(), Optional.empty(), metric,
                MetricAggregation.SUM, LeaderboardDirection.HIGHER_IS_BETTER,
                LeaderboardPeriod.allTime(), 1, 10);
    }

    public LeaderboardQuery(GameKey game, String ruleset, String mode, String metric) {
        this(game, Optional.of(ruleset), Optional.of(mode), metric,
                MetricAggregation.SUM, LeaderboardDirection.HIGHER_IS_BETTER,
                LeaderboardPeriod.allTime(), 1, 10);
    }

    public LeaderboardQuery(
            GameKey game,
            String ruleset,
            String mode,
            String metric,
            MetricAggregation aggregation,
            LeaderboardDirection direction,
            LeaderboardPeriod period,
            int minimumSamples,
            int limit) {
        this(game, Optional.ofNullable(ruleset), Optional.ofNullable(mode), metric,
                aggregation, direction, period, minimumSamples, limit);
    }

    public boolean matches(MatchResult result) {
        Objects.requireNonNull(result, "result");
        return result.game() == game
                && ruleset.map(value -> value.equals(result.ruleset())).orElse(true)
                && mode.map(value -> value.equals(result.mode())).orElse(true)
                && period.includes(result);
    }
}
