package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import java.util.Set;
import org.bukkit.entity.Player;

/** One cohesive, independently serializable group of Bukkit player facets. */
public interface FacetSnapshotHandler {
    Set<PlayerStateFacet> facets();

    /** Validates external availability before any gateway operation mutates state. */
    default void preflight() {}

    byte[] capture(Player player);

    /** Parses and validates a restore payload without mutating Bukkit state. */
    void validateRestore(byte[] payload);

    void enterTemporaryState(Player player);

    void purgeTemporaryState(Player player);

    void restore(Player player, byte[] payload);
}
