package com.ciaac.minecraft.minigames.configuration;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.World;

public record WorldReference(String name, UUID worldId) {
    public WorldReference {
        if (name == null || name.isBlank() || name.length() > 128 || name.contains("__SET_ME__")) {
            throw new IllegalArgumentException("World name is missing or invalid");
        }
        Objects.requireNonNull(worldId, "worldId");
    }

    public Optional<World> resolve(Server server) {
        Objects.requireNonNull(server, "server");
        World byId = server.getWorld(worldId);
        if (byId == null || !byId.getName().equals(name)) {
            return Optional.empty();
        }
        return Optional.of(byId);
    }
}
