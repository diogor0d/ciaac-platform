package com.ciaac.minecraft.minigames.runtime;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Creates complete admission identities while preserving the current connection epoch. */
public final class AdmissionRequestFactory {
    private final ConnectionRegistry connections;
    private final Clock clock;

    public AdmissionRequestFactory(ConnectionRegistry connections, Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<AdmissionRequest> create(Player player, GameKey game, UUID matchId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(matchId, "matchId");
        return connections.current(player.getUniqueId())
                .filter(connection -> connections.isCurrent(player, connection.id()))
                .map(connection -> new AdmissionRequest(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), matchId,
                        player.getUniqueId(), connection.id(), game, clock.instant()));
    }
}
