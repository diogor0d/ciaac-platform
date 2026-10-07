package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.entity.Player;

/**
 * Versioned boundary for Vault/LuckPerms/Essentials/claims/world-reset adapters.
 * A missing or unavailable port means the corresponding facets remain unsupported.
 */
public interface ExternalStateFacetPort {
    /** Version 2 requires operation-bound payload validation and replay-safe mutations. */
    default int contractVersion() { return 1; }

    String id();
    int snapshotVersion();
    Set<PlayerStateFacet> facets();
    default Set<GameKey> supportedGames() { return Set.copyOf(EnumSet.allOf(GameKey.class)); }
    boolean available();
    default byte[] capture(Player player) { throw contextRequired(); }

    /**
     * Validates the complete provider payload without changing external state.
     * Legacy providers must implement this before their snapshots are usable.
     */
    default void validateRestore(int snapshotVersion, byte[] payload) {
        throw new IllegalStateException("External adapter does not support restore prevalidation");
    }

    default void enterTemporaryState(Player player) { throw contextRequired(); }
    default void purgeTemporaryState(Player player) { throw contextRequired(); }
    default void restore(Player player, int snapshotVersion, byte[] payload) { throw contextRequired(); }

    default byte[] capture(Player player, PlayerStateOperation context) { throw contextRequired(); }

    default void validateRestore(PlayerStateOperation context, int snapshotVersion, byte[] payload) {
        throw contextRequired();
    }

    /** Mutations must journal operationId, bind the snapshot identity and reject conflicting replays. */
    default void enterTemporaryState(Player player, PlayerStateOperation context) { throw contextRequired(); }

    default void purgeTemporaryState(Player player, PlayerStateOperation context) { throw contextRequired(); }

    default void restore(Player player, PlayerStateOperation context, int snapshotVersion, byte[] payload) {
        throw contextRequired();
    }

    private static IllegalStateException contextRequired() {
        return new IllegalStateException("External adapter requires versioned operation context");
    }
}
