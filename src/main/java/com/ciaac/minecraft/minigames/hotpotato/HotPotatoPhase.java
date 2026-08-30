package com.ciaac.minecraft.minigames.hotpotato;

/** Lifecycle phases for one Hot Potato match instance. */
public enum HotPotatoPhase {
    DISABLED,
    IDLE,
    WAITING,
    COUNTDOWN,
    ENTRY_LOCKED,
    RUNNING,
    SUDDEN_DEATH,
    FINISHING,
    RESTORING,
    RECOVERING,
    CLOSED
}
