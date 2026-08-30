package com.ciaac.minecraft.minigames.buildbattle;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Final immutable result for one match. */
public record BuildBattleResult(
        UUID matchId,
        BuildBattleTheme theme,
        BuildBattlePlot winner,
        List<BuildBattlePlotAssignment> assignments,
        Map<BuildBattlePlot, BuildBattleScore> scores,
        BuildBattleTiePolicy tiePolicy
) {
    /** Compatibility constructor for callers that use the foundation default. */
    public BuildBattleResult(UUID matchId, BuildBattleTheme theme, BuildBattlePlot winner,
                             List<BuildBattlePlotAssignment> assignments,
                             Map<BuildBattlePlot, BuildBattleScore> scores) {
        this(matchId, theme, winner, assignments, scores,
                BuildBattleTiePolicy.AVERAGE_THEN_TOTAL_THEN_PLOT_ID);
    }

    public BuildBattleResult {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(winner, "winner");
        assignments = List.copyOf(Objects.requireNonNull(assignments, "assignments"));
        scores = Map.copyOf(Objects.requireNonNull(scores, "scores"));
        Objects.requireNonNull(tiePolicy, "tiePolicy");
    }
}
