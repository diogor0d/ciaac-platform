package com.ciaac.minecraft.minigames.paper.arena;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Resolves the connection capability issued by the separately validated login adapter. */
@FunctionalInterface
public interface ArenaConnectionResolver {
    Optional<UUID> connectionId(Player player);
}
