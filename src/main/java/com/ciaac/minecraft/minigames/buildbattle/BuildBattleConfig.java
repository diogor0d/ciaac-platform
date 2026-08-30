package com.ciaac.minecraft.minigames.buildbattle;

import java.util.Objects;

/** Immutable bounds used by the pure Build Battle domain. */
public record BuildBattleConfig(
        int minimumPlayers,
        int maximumPlayers,
        int minimumVote,
        int maximumVote,
        BuildBattleTheme theme,
        BuildBattleVotingCompletionPolicy votingCompletionPolicy,
        BuildBattleTiePolicy tiePolicy
) {
    /** Uses the strict no-abstention policy and stable plot-id tie break. */
    public BuildBattleConfig(int minimumPlayers, int maximumPlayers, int minimumVote,
                             int maximumVote, BuildBattleTheme theme) {
        this(minimumPlayers, maximumPlayers, minimumVote, maximumVote, theme,
                BuildBattleVotingCompletionPolicy.EVERY_ELIGIBLE_VOTER_RATES_EVERY_OTHER_PLOT,
                BuildBattleTiePolicy.AVERAGE_THEN_TOTAL_THEN_PLOT_ID);
    }

    /** Uses the stable plot-id tie break when only completion is configured. */
    public BuildBattleConfig(int minimumPlayers, int maximumPlayers, int minimumVote,
                             int maximumVote, BuildBattleTheme theme,
                             BuildBattleVotingCompletionPolicy votingCompletionPolicy) {
        this(minimumPlayers, maximumPlayers, minimumVote, maximumVote, theme,
                votingCompletionPolicy, BuildBattleTiePolicy.AVERAGE_THEN_TOTAL_THEN_PLOT_ID);
    }

    public BuildBattleConfig {
        if (minimumPlayers < 2) {
            throw new IllegalArgumentException("minimumPlayers must be at least 2");
        }
        if (maximumPlayers < minimumPlayers || maximumPlayers > 64) {
            throw new IllegalArgumentException("maximumPlayers must be between minimumPlayers and 64");
        }
        if (minimumVote < 1 || maximumVote < minimumVote || maximumVote > 10) {
            throw new IllegalArgumentException("vote bounds must be between 1 and 10");
        }
        theme = Objects.requireNonNull(theme, "theme");
        votingCompletionPolicy = Objects.requireNonNull(votingCompletionPolicy, "votingCompletionPolicy");
        tiePolicy = Objects.requireNonNull(tiePolicy, "tiePolicy");
    }
}
