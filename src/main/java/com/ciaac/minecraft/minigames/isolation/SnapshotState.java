package com.ciaac.minecraft.minigames.isolation;

public enum SnapshotState {
    CAPTURED,
    TEMPORARY_APPLIED,
    RESTORING,
    RESTORED,
    QUARANTINED;

    public boolean terminal() {
        return this == RESTORED || this == QUARANTINED;
    }
}
