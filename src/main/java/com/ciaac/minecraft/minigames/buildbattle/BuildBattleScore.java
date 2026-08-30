package com.ciaac.minecraft.minigames.buildbattle;

import java.util.Objects;

/** Immutable aggregate of the ballots received for a plot. */
public record BuildBattleScore(BuildBattlePlot plot, long total, int votes) {
    public BuildBattleScore {
        Objects.requireNonNull(plot, "plot");
        if (total < 0 || votes < 0) {
            throw new IllegalArgumentException("score totals cannot be negative");
        }
    }

    /** Exact average comparison is performed by the match; this is presentation-only. */
    public double average() {
        return votes == 0 ? 0.0d : (double) total / votes;
    }
}
