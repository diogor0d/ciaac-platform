package com.ciaac.minecraft.minigames.paper.auth;

import com.ciaac.minecraft.minigames.runtime.AuthenticatedSession;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.nickuc.login.api.event.bukkit.auth.AuthenticateEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/** Direct nLogin post-authentication bridge. No password or account data is read. */
public final class NLoginAuthenticationListener implements Listener {
    private final Plugin owner;
    private final Server server;
    private final ConnectionRegistry connections;
    private final AuthenticationRegistry authentication;
    private final Clock clock;
    private final Duration capabilityLifetime;
    private final Consumer<Player> authenticatedHook;

    public NLoginAuthenticationListener(
            Plugin owner,
            ConnectionRegistry connections,
            AuthenticationRegistry authentication,
            Clock clock,
            Duration capabilityLifetime,
            Consumer<Player> authenticatedHook) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.server = owner.getServer();
        this.connections = Objects.requireNonNull(connections, "connections");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.capabilityLifetime = Objects.requireNonNull(capabilityLifetime, "capabilityLifetime");
        this.authenticatedHook = Objects.requireNonNull(authenticatedHook, "authenticatedHook");
        if (capabilityLifetime.isZero() || capabilityLifetime.isNegative()
                || capabilityLifetime.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("capabilityLifetime must be in (0, 24h]");
        }
    }

    public NLoginAuthenticationListener(
            Plugin owner,
            ConnectionRegistry connections,
            AuthenticationRegistry authentication,
            Clock clock,
            Duration capabilityLifetime) {
        this(owner, connections, authentication, clock, capabilityLifetime, ignored -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAuthenticated(AuthenticateEvent event) {
        Player player = event.getPlayer();
        Runnable issue = () -> issueForCurrentConnection(player);
        if (server.isPrimaryThread()) issue.run();
        else server.getScheduler().runTask(owner, issue);
    }

    private void issueForCurrentConnection(Player player) {
        if (player == null || !player.isOnline() || server.getPlayer(player.getUniqueId()) != player) return;
        connections.current(player.getUniqueId()).ifPresent(connection -> {
            if (!connections.isCurrent(player, connection.id())) return;
            Instant now = clock.instant();
            authentication.authenticated(new AuthenticatedSession(
                    UUID.randomUUID(), player.getUniqueId(), connection.id(), now, now.plus(capabilityLifetime)));
            authenticatedHook.accept(player);
        });
    }
}
