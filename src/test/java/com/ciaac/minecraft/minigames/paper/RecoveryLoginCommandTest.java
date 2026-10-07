package com.ciaac.minecraft.minigames.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.runtime.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryLoginCommandTest {
    @Test void pendingSnapshotAllowsLoginButAuthenticatedArenaStillBlocksIt() throws Exception {
        Fixture fixture = new Fixture();
        fixture.commands.put("login", new NativeCommand("login", fixture.authMe));
        UUID playerId = UUID.randomUUID();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (self, method, args) -> method.getName().equals("getUniqueId") ? playerId : null);
        Instant now = Instant.now();
        SessionRegistry sessions = new SessionRegistry();
        PlayerSession session = new PlayerSession(UUID.randomUUID(), UUID.randomUUID(), playerId, GameKey.ARENA, now);
        session.transition(UUID.randomUUID(), SessionPhase.REQUESTED, SessionPhase.RECOVERING, now, "RESTART");
        sessions.register(session);
        AuthenticationRegistry authentication = new AuthenticationRegistry();
        SessionIsolationListener listener = new SessionIsolationListener(fixture.owner, sessions, authentication,
                new CombatPolicyRegistry(), new TemporaryItemTagger(fixture.owner), (p, s, v) -> {});
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        Object previous = field.get(null);
        Server eventServer = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (self, method, args) -> method.getName().equals("isPrimaryThread") ? true : null);
        field.set(null, eventServer);
        try {
            var pending = new PlayerCommandPreprocessEvent(player, "/login synthetic", java.util.Set.of());
            listener.onCommand(pending);
            assertFalse(pending.isCancelled());
            var other = new PlayerCommandPreprocessEvent(player, "/speed 10", java.util.Set.of());
            listener.onCommand(other);
            assertTrue(other.isCancelled());
            var cancelled = new PlayerCommandPreprocessEvent(player, "/login synthetic", java.util.Set.of());
            cancelled.setCancelled(true);
            listener.onCommand(cancelled);
            assertTrue(cancelled.isCancelled());
            authentication.authenticated(new AuthenticatedSession(UUID.randomUUID(), playerId, UUID.randomUUID(), now, now.plusSeconds(60)));
            var authenticated = new PlayerCommandPreprocessEvent(player, "/login synthetic", java.util.Set.of());
            listener.onCommand(authenticated);
            assertTrue(authenticated.isCancelled());
        } finally { field.set(null, previous); }
    }
    @Test void recoveryAcceptsOnlyTheEnabledProvidersCanonicalLoginAndAliases() throws Exception {
        Fixture fixture = new Fixture();
        fixture.commands.put("login", command("login", fixture.authMe));
        fixture.commands.put("l", fixture.commands.get("login"));
        fixture.commands.put("authme:login", fixture.commands.get("login"));
        fixture.commands.put("register", command("register", fixture.authMe));
        fixture.commands.put("logout", command("logout", fixture.authMe));
        fixture.commands.put("other:login", command("login", fixture.other));
        assertTrue(SessionIsolationListener.providerLoginCommand("/login password", fixture.owner));
        assertTrue(SessionIsolationListener.providerLoginCommand("/L password", fixture.owner));
        assertTrue(SessionIsolationListener.providerLoginCommand("/authme:login password", fixture.owner));
        assertFalse(SessionIsolationListener.providerLoginCommand("/register password password", fixture.owner));
        assertFalse(SessionIsolationListener.providerLoginCommand("/logout", fixture.owner));
        assertFalse(SessionIsolationListener.providerLoginCommand("/other:login password", fixture.owner));
        assertFalse(SessionIsolationListener.providerLoginCommand("/speed 10", fixture.owner));
        fixture.commands.put("login", new NativeCommand("login", fixture.authMe));
        fixture.commands.put("l", new NativeCommand("l", fixture.authMe));
        fixture.commands.put("authme:login", new NativeCommand("authme:login", fixture.authMe));
        assertTrue(SessionIsolationListener.providerLoginCommand("/login password", fixture.owner));
        assertTrue(SessionIsolationListener.providerLoginCommand("/l password", fixture.owner));
        assertTrue(SessionIsolationListener.providerLoginCommand("/authme:login password", fixture.owner));
        fixture.nLoginEnabled = true;
        assertFalse(SessionIsolationListener.providerLoginCommand("/login password", fixture.owner));
        fixture.authMeEnabled = false;
        assertFalse(SessionIsolationListener.providerLoginCommand("/login password", fixture.owner));
        fixture.commands.put("login", command("login", fixture.nLogin));
        assertTrue(SessionIsolationListener.providerLoginCommand("/login password", fixture.owner));
        fixture.nLoginEnabled = false;
        assertFalse(SessionIsolationListener.providerLoginCommand("/login password", fixture.owner));
    }

    private static final class Fixture {
        final Map<String, Command> commands = new HashMap<>();
        boolean authMeEnabled = true;
        boolean nLoginEnabled;
        final Plugin authMe = plugin(() -> authMeEnabled);
        final Plugin nLogin = plugin(() -> nLoginEnabled);
        final Plugin other = plugin(() -> true);
        final CommandMap commandMap = (CommandMap) Proxy.newProxyInstance(CommandMap.class.getClassLoader(),
                new Class<?>[]{CommandMap.class}, (self, method, args) -> method.getName().equals("getCommand") ? commands.get(args[0]) : null);
        final PluginManager manager = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(),
                new Class<?>[]{PluginManager.class}, (self, method, args) -> switch (method.getName()) {
                    case "getPlugin" -> args[0].equals("AuthMe") ? authMe : args[0].equals("nLogin") ? nLogin : null;
                    case "isPluginEnabled" -> args[0].equals("AuthMe") ? authMeEnabled : args[0].equals("nLogin") && nLoginEnabled;
                    default -> null;
                });
        final Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getPluginManager" -> manager;
                    case "getCommandMap" -> commandMap;
                    case "getPluginCommand" -> commands.get(args[0]) instanceof PluginCommand command ? command : null;
                    default -> null;
                });
        final Plugin owner = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getServer" -> server;
                    case "getName", "namespace" -> "fixture";
                    default -> null;
                });
    }

    private static final class NativeCommand extends Command implements PluginIdentifiableCommand {
        private final Plugin plugin;
        private NativeCommand(String name, Plugin plugin) { super(name); this.plugin = plugin; }
        @Override public Plugin getPlugin() { return plugin; }
        @Override public boolean execute(CommandSender sender, String label, String[] args) { return true; }
    }

    private static Plugin plugin(java.util.function.BooleanSupplier enabled) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (self, method, args) -> method.getName().equals("isEnabled") ? enabled.getAsBoolean() : null);
    }

    private static PluginCommand command(String name, Plugin owner) throws Exception {
        var constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
        constructor.setAccessible(true);
        return constructor.newInstance(name, owner);
    }
}
