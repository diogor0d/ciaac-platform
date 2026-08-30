package com.ciaac.minecraft.minigames.hotpotato;

import java.util.UUID;

/** Bounded evidence translated by a Paper adapter; never trusts arbitrary client coordinates. */
public record PassIntent(UUID source, UUID target, double distance, boolean lineOfSight) {
    public PassIntent { if (source == null || target == null || source.equals(target) || !Double.isFinite(distance) || distance < 0 || !lineOfSight) throw new IllegalArgumentException("invalid pass intent"); }
}
