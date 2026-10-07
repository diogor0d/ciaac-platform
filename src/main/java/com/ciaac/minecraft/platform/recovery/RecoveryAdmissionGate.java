package com.ciaac.minecraft.platform.recovery;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;

/** Mantém a admissão fechada mesmo quando o arranque da recuperação falha. */
public final class RecoveryAdmissionGate implements Listener {
    private enum State { STARTING, READY, FAILED }
    private volatile State state = State.STARTING;
    private static final String MESSAGE = "A recuperação da CIAACPlatform está indisponível. Tenta novamente depois da revisão por um administrador.";

    public synchronized void ready() {
        if (state != State.STARTING) throw new IllegalStateException("A admissão já não está a iniciar.");
        state = State.READY;
    }

    public synchronized void fail() { state = State.FAILED; }

    public boolean allowsAdmission() { return state == State.READY; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!allowsAdmission()) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(MESSAGE));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (!allowsAdmission()) event.disallow(PlayerLoginEvent.Result.KICK_OTHER, Component.text(MESSAGE));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (!allowsAdmission()) event.getPlayer().kick(Component.text(MESSAGE));
    }

    public void denyOnlinePlayers(Server server) {
        for (var player : Objects.requireNonNull(server, "server").getOnlinePlayers()) {
            player.kick(Component.text(MESSAGE));
        }
    }

    /** Remove apenas listeners do runtime; o bloqueio não depende desse runtime. */
    public static void unregisterRuntimeListeners(Plugin plugin, RecoveryAdmissionGate gate) {
        Objects.requireNonNull(gate, "gate");
        for (var registration : HandlerList.getRegisteredListeners(Objects.requireNonNull(plugin, "plugin"))) {
            if (registration.getListener() != gate) HandlerList.unregisterAll(registration.getListener());
        }
    }
}
