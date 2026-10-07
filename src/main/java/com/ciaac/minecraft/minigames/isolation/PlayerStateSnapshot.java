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
        UUID capturedConnectionId,
        GameKey game,
        Instant capturedAt,
        Map<PlayerStateFacet, byte[]> facets) {

    public PlayerStateSnapshot {
        if (schemaVersion < 1 || schemaVersion > 2) {
            throw new IllegalArgumentException("schemaVersion must be between 1 and 2");
        }
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(playerId, "playerId");
        if (schemaVersion == 1 && capturedConnectionId != null) {
            throw new IllegalArgumentException("schema 1 cannot contain a captured connection ID");
        }
        if (schemaVersion == 2 && capturedConnectionId == null) {
            throw new IllegalArgumentException("schema 2 requires a captured connection ID");
        }
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(facets, "facets");
        EnumMap<PlayerStateFacet, byte[]> copy = new EnumMap<>(PlayerStateFacet.class);
        facets.forEach((facet, payload) -> copy.put(
                Objects.requireNonNull(facet, "facet"),
                Objects.requireNonNull(payload, "payload").clone()));
        facets = Map.copyOf(copy);
    }

    /** Compatibility constructor for schema 1 snapshots created before connection identity was captured. */
    public PlayerStateSnapshot(
            int schemaVersion,
            UUID snapshotId,
            UUID operationId,
            UUID sessionId,
            UUID matchId,
            UUID playerId,
            GameKey game,
            Instant capturedAt,
            Map<PlayerStateFacet, byte[]> facets) {
        this(schemaVersion, snapshotId, operationId, sessionId, matchId, playerId, null, game, capturedAt, facets);
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
