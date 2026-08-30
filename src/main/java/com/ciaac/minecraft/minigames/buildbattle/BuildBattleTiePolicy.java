package com.ciaac.minecraft.minigames.buildbattle;

/** Deterministic ordering used when two plots have equivalent scores. */
public enum BuildBattleTiePolicy {
    /** Compare average score, then total score, then the stable plot id. */
    AVERAGE_THEN_TOTAL_THEN_PLOT_ID
}
