package com.ciaac.minecraft.minigames.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Volatile connection epochs. A connection ID is identity context, never proof
 * that nLogin authentication completed.
 */
public final class ConnectionRegistry {
    public record Connection(UUID id, UUID playerId, Instant joinedAt, Player player) {
        public Connection {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(joinedAt, "joinedAt");
            Objects.requireNonNull(player, "player");
            if (!player.getUniqueId().equals(playerId)) {
                throw new IllegalArgumentException("Player does not match connection identity");
            }
        }
    }

    private final Map<UUID, Connection> byPlayer = new LinkedHashMap<>();

    public synchronized Connection begin(Player player, Instant joinedAt) {
        Objects.requireNonNull(player, "player");
        Connection value = new Connection(
                UUID.randomUUID(), player.getUniqueId(), Objects.requireNonNull(joinedAt, "joinedAt"), player);
        byPlayer.put(player.getUniqueId(), value);
        return value;
    }

    public synchronized Optional<Connection> current(UUID playerId) {
        return Optional.ofNullable(byPlayer.get(Objects.requireNonNull(playerId, "playerId")));
    }

    public synchronized boolean isCurrent(Player player, UUID connectionId) {
        Objects.requireNonNull(player, "player");
        Connection current = byPlayer.get(player.getUniqueId());
        return current != null && current.id().equals(connectionId) && current.player() == player;
    }

    public synchronized Optional<Connection> end(Player player) {
        Objects.requireNonNull(player, "player");
        Connection current = byPlayer.get(player.getUniqueId());
        if (current == null || current.player() != player) return Optional.empty();
        byPlayer.remove(player.getUniqueId());
        return Optional.of(current);
    }
}
