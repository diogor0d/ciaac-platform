package com.ciaac.minecraft.minigames.isolation;

/** A proven, bounded world cleanup is incomplete; player state must remain isolated. */
public final class WorldRecoveryPendingException extends RuntimeException {
    public WorldRecoveryPendingException() {
        super("Verified world cleanup requires another bounded batch");
    }
}
