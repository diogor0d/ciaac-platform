package com.ciaac.minecraft.minigames.persistence;

public interface AuditRepository {
    /** Returns false for an identical replay and rejects conflicting IDs. */
    boolean append(AuditEvent event);
}
