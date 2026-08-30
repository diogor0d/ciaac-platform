package com.ciaac.minecraft.minigames.arena;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Lifecycle phases for the single reserved/active arena slot. */
public enum ArenaPhase {
    IDLE,
    WAITING,
    RESERVED_READY,
    ADMITTING,
    ACTIVE,
    FINISHING,
    RESTORING,
    RECOVERING,
    CLOSED;

    private static final Map<ArenaPhase, Set<ArenaPhase>> LEGAL_TRANSITIONS = Map.of(
            IDLE, EnumSet.of(WAITING, CLOSED),
            WAITING, EnumSet.of(RESERVED_READY, CLOSED),
            RESERVED_READY, EnumSet.of(WAITING, ADMITTING, RECOVERING, CLOSED),
            ADMITTING, EnumSet.of(ACTIVE, RECOVERING, CLOSED),
            ACTIVE, EnumSet.of(FINISHING, RECOVERING),
            FINISHING, EnumSet.of(RESTORING, RECOVERING),
            RESTORING, EnumSet.of(RECOVERING, CLOSED),
            RECOVERING, EnumSet.of(RESTORING, CLOSED),
            CLOSED, EnumSet.noneOf(ArenaPhase.class));

    public boolean canTransitionTo(ArenaPhase next) {
        return LEGAL_TRANSITIONS.get(this).contains(next);
    }
}
