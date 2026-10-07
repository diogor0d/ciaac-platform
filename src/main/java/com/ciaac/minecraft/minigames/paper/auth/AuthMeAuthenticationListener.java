package com.ciaac.minecraft.minigames.paper.auth;

import com.ciaac.minecraft.minigames.runtime.AuthenticatedSession;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Issues connection-bound capabilities after the reviewed AuthMe LoginEvent.
 * The cold-start profile disables all later login commands, transfers and
 * teleports. AuthMe's delayed inventory-view closure does not restore contents;
 * this adapter makes no claim about unrelated plugins or subsequent gameplay.
 */
public final class AuthMeAuthenticationListener implements Listener {
    interface Proof {
        boolean establish();
        boolean valid();
        boolean authenticated(Player player);
        void revoke();
    }

    private final Plugin owner;
    private final Plugin provider;
    private final ConnectionRegistry connections;
    private final AuthenticationRegistry authentication;
    private final Clock clock;
    private final Consumer<Player> authenticatedHook;
    private final Proof proof;
    private final Map<UUID, UUID> attempts = new ConcurrentHashMap<>();
    private boolean startupSeen;

    AuthMeAuthenticationListener(Plugin owner, Plugin provider, ConnectionRegistry connections,
            AuthenticationRegistry authentication, Clock clock, Consumer<Player> authenticatedHook, Proof proof) {
        this.owner = Objects.requireNonNull(owner);
        this.provider = Objects.requireNonNull(provider);
        this.connections = Objects.requireNonNull(connections);
        this.authentication = Objects.requireNonNull(authentication);
        this.clock = Objects.requireNonNull(clock);
        this.authenticatedHook = Objects.requireNonNull(authenticatedHook);
        this.proof = Objects.requireNonNull(proof);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP || startupSeen) {
            proof.revoke();
            return;
        }
        startupSeen = true;
        if (proof.establish()) {
            owner.getLogger().info("AUTHME_COMPLETION_PROFILE_READY: AuthMe LoginEvent pode concluir o restauro autenticado.");
        } else {
            owner.getLogger().severe("AUTHME_COMPLETION_PROFILE_UNAVAILABLE: admissão e recuperação permanecem fechadas.");
        }
    }

    // Called only by the binding to the actual public synchronous LoginEvent.
    void onLogin(Player player) {
        if (player == null || !startupSeen || !proof.valid()) return;
        connections.current(player.getUniqueId()).filter(connection -> connection.player() == player)
                .ifPresent(connection -> {
                    if (authentication.current(player.getUniqueId(), clock.instant())
                            .filter(auth -> auth.connectionId().equals(connection.id())).isPresent()) return;
                    UUID attempt = UUID.randomUUID();
                    attempts.put(player.getUniqueId(), attempt);
                    player.getScheduler().run(owner,
                            ignored -> issue(player, connection.id(), attempt), null);
                });
    }

    private void issue(Player player, UUID connectionId, UUID attempt) {
        if (!current(player, connectionId, attempt)) return;
        Instant now = clock.instant();
        authentication.authenticated(new AuthenticatedSession(UUID.randomUUID(), player.getUniqueId(),
                connectionId, now, now.plus(Duration.ofHours(24))), () -> current(player, connectionId, attempt));
        authenticatedHook.accept(player);
    }

    private boolean current(Player player, UUID connectionId, UUID attempt) {
        return player.isOnline() && owner.getServer().getPlayer(player.getUniqueId()) == player
                && connections.isCurrent(player, connectionId)
                && attempt.equals(attempts.get(player.getUniqueId()))
                && proof.valid() && proof.authenticated(player);
    }

    void onLogout(Player player) {
        if (player == null) return;
        connections.current(player.getUniqueId()).filter(connection -> connection.player() == player)
                .ifPresent(connection -> {
                    attempts.remove(player.getUniqueId());
                    authentication.invalidate(player.getUniqueId(), connection.id());
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        onLogout(player);
        // The core lifecycle listener may already have removed this epoch.
        // Never remove a queued attempt belonging to a replacement Player.
        if (connections.current(player.getUniqueId())
                .filter(connection -> connection.player() != player).isEmpty()) {
            attempts.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProviderDisable(PluginDisableEvent event) {
        if (event.getPlugin() == provider || event.getPlugin() == owner) proof.revoke();
    }
}
