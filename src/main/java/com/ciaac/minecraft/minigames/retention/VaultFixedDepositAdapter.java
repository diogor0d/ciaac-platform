package com.ciaac.minecraft.minigames.retention;

import java.util.Map;
import java.util.UUID;

/** Disabled-by-default Vault boundary with explicit reward and player-season caps. */
public final class VaultFixedDepositAdapter implements RewardDeliveryPort {
    public interface EconomyPort {
        /** Implementations must atomically enforce the supplied cumulative player-season cap. */
        DepositOutcome deposit(UUID operationId, UUID playerId, String seasonId,
                               long minorUnits, long playerSeasonCapMinorUnits);
    }
    public enum DepositOutcome { CONFIRMED, REJECTED, UNCERTAIN }

    private final EconomyPort economy;
    private final Map<String, Long> rewardMinorUnits;
    private final long playerSeasonCapMinorUnits;

    public VaultFixedDepositAdapter(EconomyPort economy, Map<String, Long> rewardMinorUnits,
                                    long playerSeasonCapMinorUnits) {
        this.economy = java.util.Objects.requireNonNull(economy, "economy");
        this.rewardMinorUnits = Map.copyOf(rewardMinorUnits);
        if (playerSeasonCapMinorUnits < 1 || rewardMinorUnits.isEmpty()
                || rewardMinorUnits.values().stream().anyMatch(value -> value < 1 || value > playerSeasonCapMinorUnits)) {
            throw new IllegalArgumentException("Os limites monetários do Passaporte são inválidos.");
        }
        this.playerSeasonCapMinorUnits = playerSeasonCapMinorUnits;
    }

    @Override public String id() { return "vault"; }

    @Override
    public DeliveryResult deliver(UUID operationId, UUID playerId, SeasonWindow season, RewardDescriptor reward) {
        Long amount = rewardMinorUnits.get(reward.id());
        if (amount == null || amount > playerSeasonCapMinorUnits) return DeliveryResult.UNAVAILABLE;
        return switch (economy.deposit(operationId, playerId, season.id(), amount, playerSeasonCapMinorUnits)) {
            case CONFIRMED -> DeliveryResult.DELIVERED;
            case REJECTED -> DeliveryResult.DELIVERY_PENDING;
            case UNCERTAIN -> DeliveryResult.MANUAL_REVIEW;
        };
    }
}
