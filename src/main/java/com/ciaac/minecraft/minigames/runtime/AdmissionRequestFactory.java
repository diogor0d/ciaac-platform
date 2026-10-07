package com.ciaac.minecraft.minigames.runtime;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Creates admission identities only for authenticated current connection epochs. */
public final class AdmissionRequestFactory {
    private final ConnectionRegistry connections;
    private final AuthenticationRegistry authentication;
    private final Clock clock;

    public AdmissionRequestFactory(ConnectionRegistry connections, AuthenticationRegistry authentication, Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean isAuthenticated(Player player) {
        Objects.requireNonNull(player, "player");
        return authentication.current(player.getUniqueId(), clock.instant())
                .filter(session -> connections.isCurrent(player, session.connectionId())).isPresent();
    }

    public Optional<AdmissionRequest> create(Player player, GameKey game, UUID matchId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(matchId, "matchId");
        Instant now = clock.instant();
        return authentication.current(player.getUniqueId(), now)
                .filter(session -> connections.isCurrent(player, session.connectionId()))
                .map(session -> new AdmissionRequest(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), matchId,
                        player.getUniqueId(), session.connectionId(), game, now));
    }
}
