package com.ciaac.minecraft.minigames.paper.arena.staked;

/** Live-delivery state for a claim over an exact stored payload. */
public enum StakedClaimState {
    PENDING,
    DELIVERING,
    DELIVERED,
    QUARANTINED
}
