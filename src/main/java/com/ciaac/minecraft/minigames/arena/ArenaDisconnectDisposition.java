package com.ciaac.minecraft.minigames.arena;

/** Fail-closed outcome for a disconnect observed by the arena coordinator. */
public enum ArenaDisconnectDisposition {
    READY_CHECK_RECOVERY,
    ACTIVE_FORFEIT,
    RESTORATION_RECOVERY,
    IGNORED_TERMINAL
}
