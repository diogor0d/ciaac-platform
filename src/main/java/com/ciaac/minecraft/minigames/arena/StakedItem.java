package com.ciaac.minecraft.minigames.arena;

import java.util.Objects;

/** Platform-neutral identity for one exact, canonicalized escrowed item stack. */
public record StakedItem(String itemId, String material, int amount, String canonicalFingerprint) {
    public StakedItem {
        if (Objects.requireNonNull(itemId, "itemId").isBlank()) {
            throw new IllegalArgumentException("Item id cannot be blank");
        }
        if (Objects.requireNonNull(material, "material").isBlank()) {
            throw new IllegalArgumentException("Material cannot be blank");
        }
        if (amount < 1) {
            throw new IllegalArgumentException("Item amount must be positive");
        }
        if (Objects.requireNonNull(canonicalFingerprint, "canonicalFingerprint").isBlank()) {
            throw new IllegalArgumentException("canonicalFingerprint cannot be blank");
        }
    }
}
