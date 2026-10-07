package com.ciaac.minecraft.minigames.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Platform adapter for main-thread player-state capture and restoration. */
public interface PlayerStateGateway {
    Set<PlayerStateFacet> supportedFacets();

    default Set<PlayerStateFacet> supportedFacets(GameKey game) { return supportedFacets(); }

    PlayerStateSnapshot capture(
            UUID snapshotId,
            UUID operationId,
            UUID sessionId,
            UUID matchId,
            UUID playerId,
            UUID connectionId,
            GameKey game,
            Instant capturedAt);

    /** Clears or isolates survival state only after the snapshot is durable. */
    void enterTemporaryState(UUID operationId, PlayerStateSnapshot snapshot);

    /** Removes all game-owned temporary state before restoration. */
    void purgeTemporaryState(UUID operationId, PlayerStateSnapshot snapshot);

    /** Restores captured facets; each handler must make replay safe or reject ambiguity. */
    void restore(UUID operationId, PlayerStateSnapshot snapshot);

    default boolean supports(IsolationPolicy policy) {
        return supportedFacets().containsAll(policy.protectedFacets());
    }

    default boolean supports(GameKey game, IsolationPolicy policy) {
        return supportedFacets(game).containsAll(policy.protectedFacets());
    }
}
