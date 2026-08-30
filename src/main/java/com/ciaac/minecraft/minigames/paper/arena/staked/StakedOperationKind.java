package com.ciaac.minecraft.minigames.paper.arena.staked;

/** Idempotent operation categories recorded by the staked escrow journal. */
public enum StakedOperationKind {
    PREPARE,
    VERIFY_INVENTORY,
    CONSENT,
    BEGIN_WITHDRAWAL,
    COMMIT_WITHDRAWAL,
    SETTLE,
    REFUND,
    BEGIN_DELIVERY,
    COMMIT_DELIVERY
}
