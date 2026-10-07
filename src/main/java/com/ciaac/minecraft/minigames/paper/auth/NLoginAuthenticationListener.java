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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * nLogin authentication requires separate proof that its player-state restore is
 * complete. The public AuthenticateEvent and isAuthenticated flag do not provide
 * that proof. No password or account data is read.
 */
public final class NLoginAuthenticationListener implements Listener {
    private static final StateCompletion UNSUPPORTED = (player, connectionId, completed) -> {};
    private final Plugin owner;
    private final Server server;
    private final ConnectionRegistry connections;
    private final AuthenticationRegistry authentication;
    private final Clock clock;
    private final Duration capabilityLifetime;
    private final Consumer<Player> authenticatedHook;
    private final StateCompletion stateCompletion;

    public NLoginAuthenticationListener(
            Plugin owner,
            ConnectionRegistry connections,
            AuthenticationRegistry authentication,
            Clock clock,
            Duration capabilityLifetime,
            Consumer<Player> authenticatedHook) {
        this(owner, connections, authentication, clock, capabilityLifetime, authenticatedHook, UNSUPPORTED);
    }

    NLoginAuthenticationListener(
            Plugin owner,
            ConnectionRegistry connections,
            AuthenticationRegistry authentication,
            Clock clock,
            Duration capabilityLifetime,
            Consumer<Player> authenticatedHook,
            StateCompletion stateCompletion) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.server = owner.getServer();
        this.connections = Objects.requireNonNull(connections, "connections");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.capabilityLifetime = Objects.requireNonNull(capabilityLifetime, "capabilityLifetime");
        this.authenticatedHook = Objects.requireNonNull(authenticatedHook, "authenticatedHook");
        this.stateCompletion = Objects.requireNonNull(stateCompletion, "stateCompletion");
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
        if (player == null) return;
        connections.current(player.getUniqueId())
                .filter(connection -> connection.player() == player)
                .ifPresent(connection -> {
                    AtomicBoolean completed = new AtomicBoolean();
                    stateCompletion.afterRestore(player, connection.id(), () -> {
                        if (!completed.compareAndSet(false, true)) return;
                        player.getScheduler().run(owner,
                                ignored -> issueForCurrentConnection(player, connection.id()), null);
                    });
                });
    }

    private void issueForCurrentConnection(Player player, UUID connectionId) {
        if (!player.isOnline() || server.getPlayer(player.getUniqueId()) != player
                || !connections.isCurrent(player, connectionId)) return;
        Instant now = clock.instant();
        authentication.authenticated(new AuthenticatedSession(
                UUID.randomUUID(), player.getUniqueId(), connectionId, now, now.plus(capabilityLifetime)));
        authenticatedHook.accept(player);
    }

    /**
     * Completion must follow every provider-owned state mutation, including
     * asynchronous teleports, for the specified connection. A timer or the
     * public authentication flag cannot implement this contract. API 10.4 has
     * no supported implementation, so production constructors remain closed.
     */
    @FunctionalInterface
    interface StateCompletion {
        void afterRestore(Player player, UUID connectionId, Runnable completed);
    }
}
