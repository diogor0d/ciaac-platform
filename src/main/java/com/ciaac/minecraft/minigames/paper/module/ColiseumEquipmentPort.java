package com.ciaac.minecraft.minigames.paper.module;

import com.ciaac.minecraft.minigames.arena.ArenaEquipmentContract;
import com.ciaac.minecraft.minigames.arena.ArenaKitMode;
import org.bukkit.entity.Player;

/**
 * Injected equipment decision boundary. Implementations must detach and
 * validate player state before returning a protected-survival contract.
 */
@FunctionalInterface
public interface ColiseumEquipmentPort {
    ArenaEquipmentContract contract(Player player, ArenaKitMode mode);
}
