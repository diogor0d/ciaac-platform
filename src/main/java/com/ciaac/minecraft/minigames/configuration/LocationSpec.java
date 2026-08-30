package com.ciaac.minecraft.minigames.configuration;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.Server;

public record LocationSpec(
        WorldReference world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch) {

    public LocationSpec {
        Objects.requireNonNull(world, "world");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Location coordinates must be finite");
        }
        if (pitch < -90 || pitch > 90) {
            throw new IllegalArgumentException("Location pitch must be between -90 and 90");
        }
    }

    public Optional<Location> resolve(Server server) {
        return world.resolve(server).map(value -> new Location(value, x, y, z, yaw, pitch));
    }
}
