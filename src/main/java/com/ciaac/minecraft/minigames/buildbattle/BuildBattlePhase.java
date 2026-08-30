package com.ciaac.minecraft.minigames.buildbattle;

/** Lifecycle phases for the single Build Battle instance. */
public enum BuildBattlePhase {
    IDLE,
    WAITING,
    THEME_VOTING,
    COUNTDOWN,
    BUILDING,
    REVIEWING,
    VOTING,
    RESULTS,
    RESETTING,
    RECOVERING,
    CLOSED
}
