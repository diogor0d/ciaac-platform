package com.ciaac.minecraft.minigames.paper;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class TemporaryItemTagger {
    private final NamespacedKey sessionKey;
    private final NamespacedKey gameKey;

    public TemporaryItemTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        sessionKey = new NamespacedKey(plugin, "temporary-session");
        gameKey = new NamespacedKey(plugin, "temporary-game");
    }

    public ItemStack tag(ItemStack original, UUID sessionId, GameKey game) {
        ItemStack tagged = Objects.requireNonNull(original, "original").clone();
        tagged.editPersistentDataContainer(container -> {
            container.set(sessionKey, PersistentDataType.STRING, sessionId.toString());
            container.set(gameKey, PersistentDataType.STRING, game.id());
        });
        return tagged;
    }

    public boolean isTemporary(ItemStack item) {
        return sessionId(item).isPresent();
    }

    public Optional<UUID> sessionId(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        String raw = item.getPersistentDataContainer().get(sessionKey, PersistentDataType.STRING);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
