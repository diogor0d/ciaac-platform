package com.ciaac.minecraft.minigames.arena;

import java.util.Objects;
import java.util.Optional;

/** Immutable, adapter-neutral equipment decision captured before admission. */
public record ArenaEquipmentContract(ArenaKitMode mode, String fixedKitId, String protectedLoadoutDigest) {
    public ArenaEquipmentContract {
        Objects.requireNonNull(mode, "mode");
        fixedKitId = optional(fixedKitId);
        protectedLoadoutDigest = optional(protectedLoadoutDigest);
        if (mode == ArenaKitMode.FIXED && required(fixedKitId).isEmpty()) {
            throw new IllegalArgumentException("Fixed mode requires a configured kit id");
        }
        if (mode == ArenaKitMode.FIXED && protectedLoadoutDigest != null) {
            throw new IllegalArgumentException("Fixed mode cannot carry a protected loadout");
        }
        if (mode == ArenaKitMode.MIRRORED_SURVIVAL && required(protectedLoadoutDigest).isEmpty()) {
            throw new IllegalArgumentException("Protected mode requires a loadout digest");
        }
        if (mode == ArenaKitMode.MIRRORED_SURVIVAL && fixedKitId != null) {
            throw new IllegalArgumentException("Protected mode cannot carry a fixed kit");
        }
        if (mode == ArenaKitMode.STAKED_SURVIVAL && (fixedKitId != null || protectedLoadoutDigest != null)) {
            throw new IllegalArgumentException("Staked mode cannot carry a fixed or protected loadout");
        }
    }

    public static ArenaEquipmentContract fixed(String kitId) {
        return new ArenaEquipmentContract(ArenaKitMode.FIXED, kitId, null);
    }

    public static ArenaEquipmentContract protectedCopy(String digest) {
        return new ArenaEquipmentContract(ArenaKitMode.MIRRORED_SURVIVAL, null, digest);
    }

    public static ArenaEquipmentContract staked() {
        return new ArenaEquipmentContract(ArenaKitMode.STAKED_SURVIVAL, null, null);
    }

    private static String optional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
    private static Optional<String> required(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }
}
