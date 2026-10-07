package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.luckperms.api.LuckPerms;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

class LuckPermsAuthorityBindingTest {
    @Test
    void requiresExactSupportedPluginVersion() {
        Plugin plugin = plugin("5.5.65", new AtomicBoolean(true));
        LuckPerms api = proxy(LuckPerms.class, (instance, method, args) -> null);
        ExternalStateAuthority binding = binding(plugin, plugin, registration(plugin, api));

        assertTrue(binding.available());
        assertThrows(IllegalArgumentException.class,
                () -> LuckPermsAuthorityBinding.create(server(registration(plugin, api), plugin),
                        plugin("5.5.64", new AtomicBoolean(true))));
    }

    @Test
    void rejectsServiceRegisteredByAnotherPlugin() {
        Plugin selected = plugin("5.5.65", new AtomicBoolean(true));
        Plugin owner = plugin("5.5.65", new AtomicBoolean(true));
        LuckPerms api = proxy(LuckPerms.class, (instance, method, args) -> null);

        assertThrows(IllegalStateException.class,
                () -> LuckPermsAuthorityBinding.create(server(registration(owner, api), selected), selected));
    }

    @Test
    void becomesUnavailableWhenApiProviderIsReplaced() {
        Plugin plugin = plugin("5.5.65", new AtomicBoolean(true));
        LuckPerms originalApi = proxy(LuckPerms.class, (instance, method, args) -> null);
        LuckPerms replacementApi = proxy(LuckPerms.class, (instance, method, args) -> null);
        AtomicReference<RegisteredServiceProvider<LuckPerms>> current =
                new AtomicReference<>(registration(plugin, originalApi));
        ExternalStateAuthority binding = LuckPermsAuthorityBinding.create(
                server(current, plugin), plugin);

        assertTrue(binding.available());
        current.set(registration(plugin, replacementApi));
        assertFalse(binding.available());
    }

    @Test
    void becomesUnavailableWhenPluginManagerReplacesPlugin() {
        Plugin plugin = plugin("5.5.65", new AtomicBoolean(true));
        Plugin replacement = plugin("5.5.65", new AtomicBoolean(true));
        LuckPerms api = proxy(LuckPerms.class, (instance, method, args) -> null);
        AtomicReference<Plugin> currentPlugin = new AtomicReference<>(plugin);
        ExternalStateAuthority binding = LuckPermsAuthorityBinding.create(
                server(new AtomicReference<>(registration(plugin, api)), currentPlugin), plugin);

        assertTrue(binding.available());
        currentPlugin.set(replacement);
        assertFalse(binding.available());
    }

    @Test
    void becomesUnavailableWhenSelectedPluginIsDisabled() {
        AtomicBoolean enabled = new AtomicBoolean(true);
        Plugin plugin = plugin("5.5.65", enabled);
        LuckPerms api = proxy(LuckPerms.class, (instance, method, args) -> null);
        ExternalStateAuthority binding = binding(plugin, plugin, registration(plugin, api));

        assertTrue(binding.available());
        enabled.set(false);
        assertFalse(binding.available());
    }

    private static ExternalStateAuthority binding(Plugin plugin, Plugin managedPlugin,
            RegisteredServiceProvider<LuckPerms> registration) {
        return LuckPermsAuthorityBinding.create(server(registration, managedPlugin), plugin);
    }

    private static Server server(RegisteredServiceProvider<LuckPerms> registration, Plugin managedPlugin) {
        return server(new AtomicReference<>(registration), new AtomicReference<>(managedPlugin));
    }

    private static Server server(AtomicReference<RegisteredServiceProvider<LuckPerms>> registration,
            Plugin managedPlugin) {
        return server(registration, new AtomicReference<>(managedPlugin));
    }

    private static Server server(AtomicReference<RegisteredServiceProvider<LuckPerms>> registration,
            AtomicReference<Plugin> managedPlugin) {
        ServicesManager services = proxy(ServicesManager.class, (instance, method, args) ->
                method.getName().equals("getRegistration") && args[0] == LuckPerms.class
                        ? registration.get() : null);
        PluginManager plugins = proxy(PluginManager.class, (instance, method, args) ->
                method.getName().equals("getPlugin") && args[0].equals("LuckPerms")
                        ? managedPlugin.get() : null);
        return proxy(Server.class, (instance, method, args) -> switch (method.getName()) {
            case "getServicesManager" -> services;
            case "getPluginManager" -> plugins;
            default -> null;
        });
    }

    private static Plugin plugin(String version, AtomicBoolean enabled) {
        PluginDescriptionFile description = new PluginDescriptionFile("LuckPerms", version, "test.Plugin");
        return proxy(Plugin.class, (instance, method, args) -> switch (method.getName()) {
            case "getDescription" -> description;
            case "isEnabled" -> enabled.get();
            default -> null;
        });
    }

    private static RegisteredServiceProvider<LuckPerms> registration(Plugin owner, LuckPerms api) {
        return new RegisteredServiceProvider<>(LuckPerms.class, api, ServicePriority.Normal, owner);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        Object instance = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Test proxy for " + type.getSimpleName();
                    default -> null;
                };
            }
            return handler.invoke(proxy, method, args == null ? new Object[0] : args);
        });
        return type.cast(instance);
    }
}
