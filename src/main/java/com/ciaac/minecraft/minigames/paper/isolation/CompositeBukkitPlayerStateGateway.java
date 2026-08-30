package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/** Main-thread Bukkit adapter assembled from non-overlapping facet handlers. */
public final class CompositeBukkitPlayerStateGateway implements PlayerStateGateway {
    private final Server server;
    private final List<FacetSnapshotHandler> handlers;
    private final Map<PlayerStateFacet, FacetSnapshotHandler> handlerByFacet;

    public CompositeBukkitPlayerStateGateway(Server server, List<FacetSnapshotHandler> handlers) {
        this.server = Objects.requireNonNull(server, "server");
        this.handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers"));
        EnumMap<PlayerStateFacet, FacetSnapshotHandler> indexed = new EnumMap<>(PlayerStateFacet.class);
        for (FacetSnapshotHandler handler : this.handlers) {
            Objects.requireNonNull(handler, "handler");
            if (handler.facets().isEmpty()) {
                throw new IllegalArgumentException("A facet handler must own at least one facet");
            }
            for (PlayerStateFacet facet : handler.facets()) {
                if (indexed.put(Objects.requireNonNull(facet, "facet"), handler) != null) {
                    throw new IllegalArgumentException("Multiple handlers own player-state facet " + facet);
                }
            }
        }
        this.handlerByFacet = Map.copyOf(indexed);
    }

    @Override
    public Set<PlayerStateFacet> supportedFacets() {
        return handlerByFacet.keySet();
    }

    @Override
    public PlayerStateSnapshot capture(
            UUID snapshotId,
            UUID operationId,
            UUID sessionId,
            UUID matchId,
            UUID playerId,
            GameKey game,
            Instant capturedAt) {
        preflightHandlers();
        Player player = requirePlayer(playerId);
        EnumMap<PlayerStateFacet, byte[]> payloads = new EnumMap<>(PlayerStateFacet.class);
        for (FacetSnapshotHandler handler : handlers) {
            byte[] payload = handler.capture(player);
            for (PlayerStateFacet facet : handler.facets()) {
                payloads.put(facet, payload);
            }
        }
        return new PlayerStateSnapshot(
                1,
                snapshotId,
                operationId,
                sessionId,
                matchId,
                playerId,
                game,
                capturedAt,
                payloads);
    }

    @Override
    public void enterTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {
        Objects.requireNonNull(operationId, "operationId");
        preflightHandlers();
        Player player = requirePlayer(snapshot.playerId());
        for (FacetSnapshotHandler handler : handlers) {
            handler.enterTemporaryState(player);
        }
    }

    @Override
    public void purgeTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {
        Objects.requireNonNull(operationId, "operationId");
        preflightHandlers();
        Player player = requirePlayer(snapshot.playerId());
        for (FacetSnapshotHandler handler : handlers) {
            handler.purgeTemporaryState(player);
        }
    }

    @Override
    public void restore(UUID operationId, PlayerStateSnapshot snapshot) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(snapshot, "snapshot");
        for (PlayerStateFacet facet : snapshot.capturedFacets()) {
            if (!handlerByFacet.containsKey(facet)) {
                throw new IllegalStateException("Snapshot contains an unsupported player-state facet " + facet);
            }
        }
        Map<FacetSnapshotHandler, byte[]> payloadByHandler = new LinkedHashMap<>();
        for (Map.Entry<PlayerStateFacet, FacetSnapshotHandler> entry : handlerByFacet.entrySet()) {
            byte[] payload = snapshot.requireFacet(entry.getKey());
            byte[] previous = payloadByHandler.putIfAbsent(entry.getValue(), payload);
            if (previous != null && !java.util.Arrays.equals(previous, payload)) {
                throw new IllegalStateException("Facets owned by one handler have conflicting payloads");
            }
        }
        for (FacetSnapshotHandler handler : handlers) {
            if (!payloadByHandler.containsKey(handler)) {
                throw new IllegalStateException("Snapshot is missing handler payload for " + handler.getClass());
            }
        }
        preflightHandlers();
        for (FacetSnapshotHandler handler : handlers) {
            handler.validateRestore(payloadByHandler.get(handler));
        }
        Player player = requirePlayer(snapshot.playerId());
        for (FacetSnapshotHandler handler : handlers) {
            byte[] payload = payloadByHandler.get(handler);
            handler.restore(player, payload);
        }
        player.updateInventory();
    }

    private void preflightHandlers() {
        for (FacetSnapshotHandler handler : handlers) handler.preflight();
    }

    private Player requirePlayer(UUID playerId) {
        if (!server.isPrimaryThread()) {
            throw new IllegalStateException("Bukkit player-state operations require the primary server thread");
        }
        Player player = server.getPlayer(Objects.requireNonNull(playerId, "playerId"));
        if (player == null || !player.isOnline() || !player.isValid()) {
            throw new IllegalStateException("Player is not online and valid for state processing");
        }
        return player;
    }
}
