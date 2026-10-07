package com.ciaac.minecraft.minigames.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class SessionIsolationCommandTest {
    @Test void everyDeclaredGameAliasHasCanonicalLeaveHelpAndStatusBehavior() throws Exception {
        Map<String, PluginCommand> commands = new HashMap<>();
        Plugin owner = owner(commands);
        YamlConfiguration manifest = new YamlConfiguration();
        try (var input = getClass().getResourceAsStream("/plugin.yml")) {
            manifest.load(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
        }
        var declared = manifest.getConfigurationSection("commands");
        for (String name : declared.getKeys(false)) {
            PluginCommand command = command(name, owner);
            commands.put(name, command); commands.put("ciaacplatform:" + name, command);
            for (String alias : declared.getStringList(name + ".aliases")) {
                commands.put(alias, command); commands.put("ciaacplatform:" + alias, command);
            }
        }
        for (var entry : commands.entrySet()) {
            String name = entry.getValue().getName();
            if (name.equals("ciaac") || name.equals("passaporte")) continue;
            for (String action : java.util.List.of("sair", "ajuda", "estado")) {
                if (name.equals("minijogos") && action.equals("sair")) continue;
                assertTrue(SessionIsolationListener.allowedSessionCommand("/" + entry.getKey() + " " + action, owner), entry.getKey());
            }
            assertFalse(SessionIsolationListener.allowedSessionCommand("/" + entry.getKey() + " entrar", owner));
        }
        assertTrue(SessionIsolationListener.allowedSessionCommand("/bb votar plot-1 5", owner));
        assertFalse(SessionIsolationListener.allowedSessionCommand("/arco sair extra", owner));
        assertFalse(SessionIsolationListener.allowedSessionCommand("/minecraft:arco sair", owner));
    }

    @Test void aliasOwnedByAnotherPluginCannotBypassIsolation() throws Exception {
        Map<String, PluginCommand> commands = new HashMap<>();
        Plugin owner = owner(commands); Plugin other = owner(new HashMap<>());
        commands.put("archery", command("arco", other));
        assertFalse(SessionIsolationListener.allowedSessionCommand("/archery sair", owner));
    }

    private static Plugin owner(Map<String, PluginCommand> commands) {
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (self, method, args) -> method.getName().equals("getPluginCommand") ? commands.get(args[0]) : null);
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (self, method, args) -> method.getName().equals("getServer") ? server : null);
    }
    private static PluginCommand command(String name, Plugin owner) throws Exception {
        var constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
        constructor.setAccessible(true); return constructor.newInstance(name, owner);
    }
}
