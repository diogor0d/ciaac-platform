package com.ciaac.minecraft.minigames.buildbattle;

/** Policy that determines when the voting phase may enter results. */
public enum BuildBattleVotingCompletionPolicy {
    /** Every eligible voter must rate every plot except their own. */
    EVERY_ELIGIBLE_VOTER_RATES_EVERY_OTHER_PLOT
}
