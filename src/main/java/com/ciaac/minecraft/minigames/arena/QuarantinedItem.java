package com.ciaac.minecraft.minigames.arena;

import java.util.Objects;
import java.util.UUID;

/** A rejected item retained for operator-visible quarantine, never silently deleted. */
public record QuarantinedItem(UUID owner, StakedItem item, String reason) {
    public QuarantinedItem {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(item, "item");
        if (Objects.requireNonNull(reason, "reason").isBlank()) {
            throw new IllegalArgumentException("Quarantine reason cannot be blank");
        }
    }
}
