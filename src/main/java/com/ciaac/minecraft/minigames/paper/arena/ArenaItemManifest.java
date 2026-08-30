package com.ciaac.minecraft.minigames.paper.arena;

import com.ciaac.minecraft.minigames.arena.StakedItem;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable Paper-neutral result of inspecting one player's offered items. */
public record ArenaItemManifest(UUID owner, List<StakedItem> items, Map<String, String> prohibitedItems) {
    public ArenaItemManifest {
        Objects.requireNonNull(owner, "owner");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        prohibitedItems = Map.copyOf(Objects.requireNonNull(prohibitedItems, "prohibitedItems"));
    }

    public boolean admissible() { return prohibitedItems.isEmpty(); }
}
