package com.ciaac.minecraft.minigames.paper.arena;

import java.util.List;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/** Supplies reviewed fixed-kit contents without giving the controller config authority. */
@FunctionalInterface
public interface ArenaKitProvider {
    Optional<List<ItemStack>> find(String kitId);
}
