package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.entity.Player;

/** One cohesive, independently serializable group of Bukkit player facets. */
public interface FacetSnapshotHandler {
    Set<PlayerStateFacet> facets();

    default Set<GameKey> supportedGames() { return Set.copyOf(EnumSet.allOf(GameKey.class)); }

    /** Validates external availability before any gateway operation mutates state. */
    default void preflight() {}

    default void preflight(GameKey game) {
        if (!supportedGames().contains(game)) {
            throw new IllegalStateException("Facet handler does not support game " + game);
        }
        preflight();
    }

    byte[] capture(Player player);

    /** Parses and validates a restore payload without mutating Bukkit state. */
    void validateRestore(byte[] payload);

    void enterTemporaryState(Player player);

    void purgeTemporaryState(Player player);

    void restore(Player player, byte[] payload);

    default byte[] capture(Player player, PlayerStateOperation context) { return capture(player); }

    default void validateRestore(PlayerStateOperation context, byte[] payload) { validateRestore(payload); }

    default void enterTemporaryState(Player player, PlayerStateOperation context) { enterTemporaryState(player); }

    default void purgeTemporaryState(Player player, PlayerStateOperation context) { purgeTemporaryState(player); }

    default void restore(Player player, PlayerStateOperation context, byte[] payload) { restore(player, payload); }
}
