package com.ciaac.minecraft.platform.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.Test;

class RecoveryAdmissionGateTest {
    @Test void pendingAndFailedBootstrapDenyLoginAndOnlySuccessfulBootstrapOpensIt() throws Exception {
        RecoveryAdmissionGate gate = new RecoveryAdmissionGate();
        AsyncPlayerPreLoginEvent pending = event(); gate.onPreLogin(pending);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, pending.getLoginResult());
        gate.ready(); AsyncPlayerPreLoginEvent ready = event(); gate.onPreLogin(ready);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, ready.getLoginResult());
        gate.fail(); AsyncPlayerPreLoginEvent failed = event(); gate.onPreLogin(failed);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, failed.getLoginResult());
        assertThrows(IllegalStateException.class, gate::ready);
    }

    @Test void runtimeCleanupKeepsGateRegisteredAfterFailure() {
        RecoveryAdmissionGate gate = new RecoveryAdmissionGate();
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (self, method, args) -> method.getName().equals("equals") ? self == args[0] :
                        method.getName().equals("hashCode") ? System.identityHashCode(self) : null);
        HandlerList handlers = new HandlerList();
        RegisteredListener guard = new RegisteredListener(gate, (listener, event) -> {}, EventPriority.HIGHEST, plugin, false);
        RegisteredListener runtime = new RegisteredListener(new Listener() {}, (listener, event) -> {}, EventPriority.NORMAL, plugin, false);
        handlers.register(guard); handlers.register(runtime);
        try {
            gate.fail(); RecoveryAdmissionGate.unregisterRuntimeListeners(plugin, gate);
            assertArrayEquals(new RegisteredListener[]{guard}, handlers.getRegisteredListeners());
            assertFalse(gate.allowsAdmission());
        } finally { handlers.unregister(plugin); }
    }

    @Test void queuedLoginsAndAlreadyOnlinePlayersRemainProtectedAfterFailure() throws Exception {
        AtomicInteger kicks = new AtomicInteger();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (self, method, args) -> { if (method.getName().equals("kick")) kicks.incrementAndGet(); return null; });
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (self, method, args) -> method.getName().equals("getOnlinePlayers") ? List.of(player) : null);
        RecoveryAdmissionGate gate = new RecoveryAdmissionGate();
        gate.ready();
        PlayerLoginEvent ready = new PlayerLoginEvent(player, "synthetic.invalid", InetAddress.getByName("203.0.113.1"));
        gate.onLogin(ready);
        assertEquals(PlayerLoginEvent.Result.ALLOWED, ready.getResult());
        gate.onJoin(new PlayerJoinEvent(player, Component.empty()));
        assertEquals(0, kicks.get());
        gate.fail();
        gate.onLogin(ready);
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER, ready.getResult());
        gate.onJoin(new PlayerJoinEvent(player, Component.empty()));
        gate.denyOnlinePlayers(server);
        assertEquals(2, kicks.get());
    }

    private static AsyncPlayerPreLoginEvent event() throws Exception {
        PlayerProfile profile = (PlayerProfile) Proxy.newProxyInstance(PlayerProfile.class.getClassLoader(),
                new Class<?>[]{PlayerProfile.class}, (self, method, args) -> null);
        return new AsyncPlayerPreLoginEvent("SyntheticPlayer", InetAddress.getByName("203.0.113.1"),
                UUID.randomUUID(), false, profile);
    }
}
