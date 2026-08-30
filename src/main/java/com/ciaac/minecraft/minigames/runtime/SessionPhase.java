package com.ciaac.minecraft.minigames.runtime;

/** Durable lifecycle for one player's participation in one minigame match. */
public enum SessionPhase {
    REQUESTED,
    SNAPSHOTTING,
    SNAPSHOT_COMMITTED,
    PREPARING,
    ACTIVE,
    FINISHING,
    RESTORING,
    RECOVERING,
    QUARANTINED,
    CLOSED;

    public boolean terminal() {
        return this == QUARANTINED || this == CLOSED;
    }

    /** True once a durable snapshot exists and survival progress must stay frozen. */
    public boolean isolationActive() {
        return switch (this) {
            case SNAPSHOT_COMMITTED, PREPARING, ACTIVE, FINISHING, RESTORING, RECOVERING, QUARANTINED -> true;
            case REQUESTED, SNAPSHOTTING, CLOSED -> false;
        };
    }
}
