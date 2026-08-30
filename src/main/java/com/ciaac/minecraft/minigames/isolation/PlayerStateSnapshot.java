package com.ciaac.minecraft.minigames.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable, operation-bound snapshot payload split by protected facet. */
public record PlayerStateSnapshot(
        int schemaVersion,
        UUID snapshotId,
        UUID operationId,
        UUID sessionId,
        UUID matchId,
        UUID playerId,
        GameKey game,
        Instant capturedAt,
        Map<PlayerStateFacet, byte[]> facets) {

    public PlayerStateSnapshot {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(facets, "facets");
        EnumMap<PlayerStateFacet, byte[]> copy = new EnumMap<>(PlayerStateFacet.class);
        facets.forEach((facet, payload) -> copy.put(
                Objects.requireNonNull(facet, "facet"),
                Objects.requireNonNull(payload, "payload").clone()));
        facets = Map.copyOf(copy);
    }

    @Override
    public Map<PlayerStateFacet, byte[]> facets() {
        EnumMap<PlayerStateFacet, byte[]> copy = new EnumMap<>(PlayerStateFacet.class);
        facets.forEach((facet, payload) -> copy.put(facet, payload.clone()));
        return Map.copyOf(copy);
    }

    public byte[] requireFacet(PlayerStateFacet facet) {
        byte[] payload = facets.get(Objects.requireNonNull(facet, "facet"));
        if (payload == null) {
            throw new IllegalStateException("Snapshot is missing facet " + facet);
        }
        return payload.clone();
    }

    public Set<PlayerStateFacet> capturedFacets() {
        return facets.keySet();
    }
}
