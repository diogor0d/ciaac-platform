package com.ciaac.minecraft.minigames.paper.arena.staked;

import com.ciaac.minecraft.minigames.paper.arena.StakedEscrowPort;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministically reconciles durable stakes whose live match disappeared on
 * a clean process restart. Ambiguous in-flight mutations remain quarantined
 * for an operator and are never guessed or replayed.
 */
public final class StakedEscrowRecoveryService {
    private StakedEscrowRecoveryService() { }

    public static RecoveryReport reconcile(StakedEscrowPort escrow) {
        Objects.requireNonNull(escrow, "escrow");
        int refunded = 0;
        int claimable = 0;
        int blocked = 0;
        for (StakedEscrowSnapshot snapshot : escrow.outstandingEscrows()) {
            switch (snapshot.state()) {
                case PREPARED, CONSENTED, WITHDRAWN -> {
                    Map<UUID, UUID> selfRefund = new LinkedHashMap<>();
                    snapshot.participants().stream().sorted().forEach(player -> selfRefund.put(player, player));
                    UUID resultId = stableId("startup-refund-result", snapshot.escrowId());
                    StakedOperationResult outcome = escrow.refund(new StakedEscrowPort.RefundRequest(
                            stableId("startup-refund-operation", snapshot.escrowId()),
                            snapshot.escrowId(), resultId, "SERVER_RESTART_RECOVERY", selfRefund));
                    if (outcome.accepted()) refunded++;
                    else blocked++;
                }
                case SETTLED, DELIVERY_PENDING -> claimable++;
                case WITHDRAWING, REFUNDING, DELIVERING, QUARANTINED -> blocked++;
                case DELIVERED -> { }
            }
        }
        return new RecoveryReport(refunded, claimable, blocked);
    }

    private static UUID stableId(String purpose, UUID escrowId) {
        return UUID.nameUUIDFromBytes((purpose + ":" + escrowId).getBytes(StandardCharsets.UTF_8));
    }

    public record RecoveryReport(int refundedEscrows, int claimableEscrows, int blockedEscrows) {
        public RecoveryReport {
            if (refundedEscrows < 0 || claimableEscrows < 0 || blockedEscrows < 0) {
                throw new IllegalArgumentException("recovery counters cannot be negative");
            }
        }

        public boolean safeForNewStakes() { return blockedEscrows == 0; }
    }
}
