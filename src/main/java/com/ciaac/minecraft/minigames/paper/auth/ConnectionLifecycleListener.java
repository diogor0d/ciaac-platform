package com.ciaac.minecraft.minigames.paper.auth;

import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import java.time.Clock;
import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Establishes connection epochs only. PlayerJoinEvent deliberately does not
 * grant an authentication capability.
 */
public final class ConnectionLifecycleListener implements Listener {
    private final ConnectionRegistry connections;
    private final AuthenticationRegistry authentication;
    private final Clock clock;

    public ConnectionLifecycleListener(
            ConnectionRegistry connections, AuthenticationRegistry authentication, Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        authentication.invalidatePlayer(event.getPlayer().getUniqueId());
        connections.begin(event.getPlayer(), clock.instant());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        connections.end(event.getPlayer()).ifPresent(connection ->
                authentication.invalidate(event.getPlayer().getUniqueId(), connection.id()));
    }
}
