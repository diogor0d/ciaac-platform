package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
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
    private final AuthenticationRegistry authentication;
    private final ConnectionRegistry connections;
    private final Clock clock;

    public CompositeBukkitPlayerStateGateway(Server server, List<FacetSnapshotHandler> handlers,
            AuthenticationRegistry authentication, ConnectionRegistry connections, Clock clock) {
        this.server = Objects.requireNonNull(server, "server");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
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
    public Set<PlayerStateFacet> supportedFacets(GameKey game) {
        Objects.requireNonNull(game, "game");
        EnumSet<PlayerStateFacet> supported = EnumSet.noneOf(PlayerStateFacet.class);
        for (FacetSnapshotHandler handler : handlers) {
            if (handler.supportedGames().contains(game)) supported.addAll(handler.facets());
        }
        return Set.copyOf(supported);
    }

    @Override
    public PlayerStateSnapshot capture(
            UUID snapshotId,
            UUID operationId,
            UUID sessionId,
            UUID matchId,
            UUID playerId,
            UUID connectionId,
            GameKey game,
            Instant capturedAt) {
        requirePrimaryThread();
        preflightHandlers(game);
        UUID currentConnection = authenticatedConnection(playerId);
        if (!currentConnection.equals(connectionId)) {
            throw new IllegalStateException("Capture connection is no longer authenticated");
        }
        PlayerStateOperation context = new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE,
                operationId, operationId, snapshotId, sessionId, matchId, playerId,
                connectionId, connectionId, game, capturedAt);
        Player player = requirePlayer(playerId, currentConnection);
        EnumMap<PlayerStateFacet, byte[]> payloads = new EnumMap<>(PlayerStateFacet.class);
        for (FacetSnapshotHandler handler : handlers) {
            requirePlayer(playerId, currentConnection);
            byte[] payload = handler.capture(player, context);
            for (PlayerStateFacet facet : handler.facets()) {
                payloads.put(facet, payload);
            }
        }
        requirePlayer(playerId, currentConnection);
        return new PlayerStateSnapshot(
                2,
                snapshotId,
                operationId,
                sessionId,
                matchId,
                playerId,
                connectionId,
                game,
                capturedAt,
                payloads);
    }

    @Override
    public void enterTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {
        Objects.requireNonNull(operationId, "operationId");
        PlayerStateOperation context = operation(PlayerStateOperation.Kind.ENTER, operationId, snapshot);
        validatedPayloads(snapshot, context);
        Player player = requirePlayer(snapshot.playerId(), context.connectionId());
        for (FacetSnapshotHandler handler : handlers) {
            requirePlayer(snapshot.playerId(), context.connectionId());
            handler.enterTemporaryState(player, context);
        }
        requirePlayer(snapshot.playerId(), context.connectionId());
    }

    @Override
    public void purgeTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {
        Objects.requireNonNull(operationId, "operationId");
        PlayerStateOperation context = operation(PlayerStateOperation.Kind.PURGE, operationId, snapshot);
        validatedPayloads(snapshot, context);
        Player player = requirePlayer(snapshot.playerId(), context.connectionId());
        for (FacetSnapshotHandler handler : handlers) {
            requirePlayer(snapshot.playerId(), context.connectionId());
            handler.purgeTemporaryState(player, context);
        }
        requirePlayer(snapshot.playerId(), context.connectionId());
    }

    @Override
    public void restore(UUID operationId, PlayerStateSnapshot snapshot) {
        Objects.requireNonNull(operationId, "operationId");
        PlayerStateOperation context = operation(PlayerStateOperation.Kind.RESTORE, operationId, snapshot);
        Map<FacetSnapshotHandler, byte[]> payloadByHandler = validatedPayloads(snapshot, context);
        Player player = requirePlayer(snapshot.playerId(), context.connectionId());
        for (FacetSnapshotHandler handler : handlers) {
            requirePlayer(snapshot.playerId(), context.connectionId());
            byte[] payload = payloadByHandler.get(handler);
            handler.restore(player, context, payload);
        }
        requirePlayer(snapshot.playerId(), context.connectionId());
        player.updateInventory();
    }

    private Map<FacetSnapshotHandler, byte[]> validatedPayloads(PlayerStateSnapshot snapshot, PlayerStateOperation context) {
        Objects.requireNonNull(snapshot, "snapshot");
        requirePrimaryThread();
        requireSupportedGame(snapshot.game());
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
        for (FacetSnapshotHandler handler : handlers) handler.preflight(snapshot.game());
        for (FacetSnapshotHandler handler : handlers) {
            handler.validateRestore(context, payloadByHandler.get(handler).clone());
        }
        return payloadByHandler;
    }

    private void preflightHandlers(GameKey game) {
        requireSupportedGame(game);
        for (FacetSnapshotHandler handler : handlers) handler.preflight(game);
    }

    private void requireSupportedGame(GameKey game) {
        Objects.requireNonNull(game, "game");
        if (!supportedFacets(game).containsAll(handlerByFacet.keySet())) {
            throw new IllegalStateException("Player-state isolation does not support game " + game.id());
        }
    }

    private PlayerStateOperation operation(PlayerStateOperation.Kind kind, UUID operationId, PlayerStateSnapshot snapshot) {
        requirePrimaryThread();
        Objects.requireNonNull(snapshot, "snapshot");
        return PlayerStateOperation.fromSnapshot(kind, operationId, snapshot, authenticatedConnection(snapshot.playerId()));
    }

    private UUID authenticatedConnection(UUID playerId) {
        var auth = authentication.current(playerId, clock.instant())
                .orElseThrow(() -> new IllegalStateException("State operation requires current authentication"));
        var connection = connections.current(playerId)
                .orElseThrow(() -> new IllegalStateException("State operation requires a live connection"));
        if (!auth.connectionId().equals(connection.id())) {
            throw new IllegalStateException("State operation authentication belongs to a stale connection");
        }
        return connection.id();
    }

    private Player requirePlayer(UUID playerId, UUID connectionId) {
        requirePrimaryThread();
        Player player = server.getPlayer(Objects.requireNonNull(playerId, "playerId"));
        if (player == null || !player.isOnline() || !player.isValid()
                || !connections.isCurrent(player, connectionId)
                || !authenticatedConnection(playerId).equals(connectionId)) {
            throw new IllegalStateException("Player is not online and valid for state processing");
        }
        return player;
    }

    private void requirePrimaryThread() {
        if (!server.isPrimaryThread()) {
            throw new IllegalStateException("Bukkit player-state operations require the primary server thread");
        }
    }
}
