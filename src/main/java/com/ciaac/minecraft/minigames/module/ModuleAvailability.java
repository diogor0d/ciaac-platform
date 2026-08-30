package com.ciaac.minecraft.minigames.module;

/** Stable cross-game states consumed by commands, displays and integrations. */
public enum ModuleAvailability {
    CLOSED,
    WAITING,
    STARTING,
    RUNNING,
    VOTING,
    FINISHING,
    RECOVERY
}
