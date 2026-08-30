package com.ciaac.minecraft.minigames.statistics;

/** Terminal classification used by statistics and recovery projections. */
public enum MatchOutcome {
    VICTORY,
    DRAW,
    NO_CONTEST,
    CANCELLED,
    ABORTED;

    /**
     * Whether this terminal outcome is eligible for competitive projections.
     *
     * <p>Cancelled, aborted, and no-contest results remain immutable evidence,
     * but must not contribute to wins, rates, or leaderboard samples.</p>
     */
    public boolean ranked() {
        return this == VICTORY || this == DRAW;
    }
}
