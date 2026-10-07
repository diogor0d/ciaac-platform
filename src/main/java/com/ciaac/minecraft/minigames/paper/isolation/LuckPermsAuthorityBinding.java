package com.ciaac.minecraft.minigames.paper.isolation;

import net.luckperms.api.LuckPerms;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/** Optional API linkage stays here; the authority is pinned to the selected plugin and service. */
final class LuckPermsAuthorityBinding {
    private static final String SUPPORTED_VERSION = "5.5.65";

    private LuckPermsAuthorityBinding() {}

    static ExternalStateAuthority create(Server server, Plugin plugin) {
        if (!SUPPORTED_VERSION.equals(plugin.getDescription().getVersion())) {
            throw new IllegalArgumentException("Unsupported LuckPerms plugin version");
        }
        var selected = server.getServicesManager().getRegistration(LuckPerms.class);
        if (selected == null || selected.getPlugin() != plugin) {
            throw new IllegalStateException("Selected LuckPerms service belongs to another plugin");
        }
        LuckPerms api = selected.getProvider();
        return new LuckPermsStateAuthority(api, () -> {
            var current = server.getServicesManager().getRegistration(LuckPerms.class);
            return plugin.isEnabled() && server.getPluginManager().getPlugin("LuckPerms") == plugin
                    && SUPPORTED_VERSION.equals(plugin.getDescription().getVersion())
                    && current != null && current.getPlugin() == plugin && current.getProvider() == api;
        });
    }
}
