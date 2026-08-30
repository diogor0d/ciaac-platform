package com.ciaac.minecraft.minigames.statistics;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.Objects;

/** One exact, comparable ruleset/mode scope for a public leaderboard. */
public record LeaderboardScope(GameKey game, String ruleset, String mode, String metric) {
    public LeaderboardScope {
        game = Objects.requireNonNull(game, "game");
        ruleset = StatisticsIdentifiers.identifier(ruleset, "ruleset");
        mode = StatisticsIdentifiers.identifier(mode, "mode");
        metric = StatisticsIdentifiers.metricKey(metric);
    }
}
