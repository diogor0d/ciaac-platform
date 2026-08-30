package com.ciaac.minecraft.minigames.retention;

import java.time.Clock;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/** Persistent embedded `%ciaac_passport_*%` expansion consumed by TAB and the configured chat formatter. */
public final class PassportPlaceholderExpansion extends PlaceholderExpansion {
    private final Plugin plugin;
    private final PassportPlaceholderValues values;
    private final Clock clock;

    public PassportPlaceholderExpansion(Plugin plugin, PassportService service, Clock clock) {
        this.plugin = plugin; this.values = new PassportPlaceholderValues(service); this.clock = clock;
    }

    @Override public @NotNull String getIdentifier() { return "ciaac_passport"; }
    @Override public @NotNull String getAuthor() { return "CIAAC"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public String onRequest(OfflinePlayer player, @NotNull String params) {
        return player == null ? null : values.value(player.getUniqueId(), params, clock.instant()).orElse(null);
    }
}
