package com.ciaac.minecraft.minigames.paper.arena;

import com.ciaac.minecraft.minigames.arena.StakedConsent;
import com.ciaac.minecraft.minigames.arena.StakedEscrow;
import com.ciaac.minecraft.minigames.paper.arena.staked.StakedClaim;
import com.ciaac.minecraft.minigames.paper.arena.staked.StakedDeliveryStart;
import com.ciaac.minecraft.minigames.paper.arena.staked.StakedEscrowSnapshot;
import com.ciaac.minecraft.minigames.paper.arena.staked.StakedInventoryPayload;
import com.ciaac.minecraft.minigames.paper.arena.staked.StakedOperationResult;
import com.ciaac.minecraft.minigames.paper.arena.staked.StakedRecoveryInspection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Required durable boundary for staked play. No in-memory implementation is
 * supplied: without this port a controller must reject staked admission.
 */
public interface StakedEscrowPort {
    default Optional<StakedEscrow> find(UUID escrowId) { return Optional.empty(); }

    default Optional<StakedEscrowSnapshot> snapshot(UUID escrowId) { return Optional.empty(); }

    /** Persists a complete offer only after all blacklist and payload checks pass. */
    default StakedOperationResult prepare(PrepareRequest request) {
        throw unsupported();
    }

    /** Compares live bytes with the exact persisted payload before any removal. */
    default StakedOperationResult verifyInventory(InventoryVerificationRequest request) {
        throw unsupported();
    }

    default StakedOperationResult consent(ConsentRequest request) {
        throw unsupported();
    }

    /** Marks the participant as ambiguous until the live adapter commits removal. */
    default StakedOperationResult beginWithdrawal(WithdrawalRequest request) {
        throw unsupported();
    }

    default StakedOperationResult commitWithdrawal(WithdrawalCommitRequest request) {
        throw unsupported();
    }

    default StakedOperationResult settle(SettlementRequest request) {
        throw unsupported();
    }

    default StakedOperationResult refund(RefundRequest request) {
        throw unsupported();
    }

    default List<StakedClaim> pendingClaims(UUID escrowId) { return List.of(); }

    /** Finds claims that a player can explicitly collect after a later login. */
    default List<StakedClaim> pendingClaimsFor(UUID beneficiaryId) { return List.of(); }

    /** Read-only capacity preview; it never reserves or mutates a delivery. */
    default Optional<StakedInventoryPayload> previewClaim(UUID escrowId, UUID claimId,
                                                          UUID beneficiaryId) {
        return Optional.empty();
    }

    /** Durable non-terminal escrows inspected during startup recovery. */
    default List<StakedEscrowSnapshot> outstandingEscrows() { return List.of(); }

    /** Reserves one claim and returns its exact bytes for the live adapter. */
    default StakedDeliveryStart beginDelivery(DeliveryBeginRequest request) {
        throw unsupported();
    }

    default StakedOperationResult commitDelivery(DeliveryCommitRequest request) {
        throw unsupported();
    }

    default Optional<StakedRecoveryInspection> blockingRecovery(UUID escrowId) { return Optional.empty(); }

    /**
     * Legacy hooks cannot be safely implemented without exact payload bytes;
     * they remain source-compatible but fail closed when called.
     */
    @Deprecated
    default void persistPrepared(UUID operationId, StakedEscrow escrow) { throw unsupported(); }

    @Deprecated
    default void persistConsent(UUID operationId, StakedConsent consent) { throw unsupported(); }

    @Deprecated
    default void persistRefund(UUID operationId, UUID escrowId, UUID resultId, String reason) { throw unsupported(); }

    @Deprecated
    default void persistSettlement(UUID operationId, UUID escrowId, UUID resultId, UUID winner) { throw unsupported(); }

    record PrepareRequest(UUID operationId, StakedEscrow escrow,
                          Map<UUID, StakedInventoryPayload> payloads,
                          Set<String> blacklistedMaterials) {
        public PrepareRequest {
            java.util.Objects.requireNonNull(operationId, "operationId");
            java.util.Objects.requireNonNull(escrow, "escrow");
            payloads = Map.copyOf(java.util.Objects.requireNonNull(payloads, "payloads"));
            blacklistedMaterials = Set.copyOf(java.util.Objects.requireNonNull(blacklistedMaterials,
                    "blacklistedMaterials"));
        }
    }

    record InventoryVerificationRequest(UUID operationId, UUID escrowId, UUID playerId,
                                        StakedInventoryPayload payload) {
        public InventoryVerificationRequest {
            java.util.Objects.requireNonNull(operationId, "operationId");
            java.util.Objects.requireNonNull(escrowId, "escrowId");
            java.util.Objects.requireNonNull(playerId, "playerId");
            java.util.Objects.requireNonNull(payload, "payload");
        }
    }

    record ConsentRequest(UUID operationId, UUID escrowId, StakedConsent consent) {
        public ConsentRequest {
            java.util.Objects.requireNonNull(operationId, "operationId");
            java.util.Objects.requireNonNull(escrowId, "escrowId");
            java.util.Objects.requireNonNull(consent, "consent");
        }
    }

    record WithdrawalRequest(UUID operationId, UUID escrowId, UUID playerId, String payloadDigest) {
        public WithdrawalRequest {
            java.util.Objects.requireNonNull(operationId, "operationId");
            java.util.Objects.requireNonNull(escrowId, "escrowId");
            java.util.Objects.requireNonNull(playerId, "playerId");
            payloadDigest = requireDigest(payloadDigest, "payloadDigest");
        }
    }

    record WithdrawalCommitRequest(UUID operationId, UUID beginOperationId, UUID escrowId,
                                   UUID playerId, String payloadDigest) {
        public WithdrawalCommitRequest {
            java.util.Objects.requireNonNull(operationId, "operationId");
            java.util.Objects.requireNonNull(beginOperationId, "beginOperationId");
            java.util.Objects.requireNonNull(escrowId, "escrowId");
            java.util.Objects.requireNonNull(playerId, "playerId");
            payloadDigest = requireDigest(payloadDigest, "payloadDigest");
        }
    }

    record SettlementRequest(UUID operationId, UUID escrowId, UUID resultId, UUID winnerId) {
        public SettlementRequest {
            java.util.Objects.requireNonNull(operationId, "operationId");
            java.util.Objects.requireNonNull(escrowId, "escrowId");
            java.util.Objects.requireNonNull(resultId, "resultId");
            java.util.Objects.requireNonNull(winnerId, "winnerId");
        }
    }

    record RefundRequest(UUID operationId, UUID escrowId, UUID resultId, String reason,
                         Map<UUID, UUID> beneficiaries) {
        public RefundRequest {
            java.util.Objects.requireNonNull(operationId, "operationId");
            java.util.Objects.requireNonNull(escrowId, "escrowId");
            java.util.Objects.requireNonNull(resultId, "resultId");
            reason = requireCode(reason, "reason");
            beneficiaries = Map.copyOf(java.util.Objects.requireNonNull(beneficiaries, "beneficiaries"));
        }
    }

    record DeliveryBeginRequest(UUID operationId, UUID escrowId, UUID claimId,
                                UUID beneficiaryId, String payloadDigest) {
        public DeliveryBeginRequest {
            java.util.Objects.requireNonNull(operationId, "operationId");
            java.util.Objects.requireNonNull(escrowId, "escrowId");
            java.util.Objects.requireNonNull(claimId, "claimId");
            java.util.Objects.requireNonNull(beneficiaryId, "beneficiaryId");
            payloadDigest = requireDigest(payloadDigest, "payloadDigest");
        }
    }

    record DeliveryCommitRequest(UUID operationId, UUID beginOperationId, UUID escrowId,
                                 UUID claimId, String payloadDigest) {
        public DeliveryCommitRequest {
            java.util.Objects.requireNonNull(operationId, "operationId");
            java.util.Objects.requireNonNull(beginOperationId, "beginOperationId");
            java.util.Objects.requireNonNull(escrowId, "escrowId");
            java.util.Objects.requireNonNull(claimId, "claimId");
            payloadDigest = requireDigest(payloadDigest, "payloadDigest");
        }
    }

    private static String requireDigest(String value, String name) {
        String normalized = java.util.Objects.requireNonNull(value, name).trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
        }
        return normalized;
    }

    private static String requireCode(String value, String name) {
        String normalized = java.util.Objects.requireNonNull(value, name).trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException(name + " must be a bounded machine-readable code");
        }
        return normalized;
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("É necessária uma implementação durável do depósito de apostas");
    }
}
