package com.ciaac.minecraft.minigames.paper.arena.staked;

/**
 * Durable lifecycle for one staked escrow.  WITHDRAWING and DELIVERING are
 * deliberately non-terminal: an interrupted live mutation must be surfaced
 * as a recovery block and never retried automatically.
 */
public enum StakedEscrowState {
    PREPARED,
    CONSENTED,
    WITHDRAWING,
    WITHDRAWN,
    SETTLED,
    REFUNDING,
    DELIVERY_PENDING,
    DELIVERING,
    DELIVERED,
    QUARANTINED;

    public boolean terminal() {
        return this == DELIVERED || this == QUARANTINED;
    }

    public boolean recoveryBlocking() {
        return this == WITHDRAWING || this == REFUNDING || this == DELIVERING || this == QUARANTINED;
    }

    public boolean canTransitionTo(StakedEscrowState next) {
        if (next == this) return true;
        return switch (this) {
            case PREPARED -> next == CONSENTED || next == REFUNDING || next == QUARANTINED;
            case CONSENTED -> next == WITHDRAWING || next == REFUNDING || next == QUARANTINED;
            case WITHDRAWING -> next == CONSENTED || next == WITHDRAWN || next == QUARANTINED;
            case WITHDRAWN -> next == SETTLED || next == REFUNDING || next == QUARANTINED;
            case SETTLED -> next == DELIVERY_PENDING || next == DELIVERING || next == QUARANTINED;
            case REFUNDING -> next == DELIVERY_PENDING || next == DELIVERED || next == QUARANTINED;
            case DELIVERY_PENDING -> next == DELIVERING || next == DELIVERED || next == QUARANTINED;
            case DELIVERING -> next == DELIVERY_PENDING || next == DELIVERED || next == QUARANTINED;
            case DELIVERED, QUARANTINED -> false;
        };
    }
}
