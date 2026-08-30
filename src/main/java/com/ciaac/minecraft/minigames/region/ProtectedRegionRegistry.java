package com.ciaac.minecraft.minigames.region;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.Block;

public final class ProtectedRegionRegistry {
    private final Map<String, ProtectedRegion> byId = new LinkedHashMap<>();
    private final Map<UUID, List<ProtectedRegion>> byWorld = new LinkedHashMap<>();

    public synchronized void register(ProtectedRegion region) {
        Objects.requireNonNull(region, "region");
        if (byId.containsKey(region.id())) {
            throw new IllegalArgumentException("Duplicate protected region " + region.id());
        }
        List<ProtectedRegion> worldRegions = byWorld.computeIfAbsent(
                region.bounds().worldId(), ignored -> new ArrayList<>());
        for (ProtectedRegion existing : worldRegions) {
            if (existing.bounds().intersects(region.bounds())) {
                throw new IllegalArgumentException(
                        "Protected regions overlap: " + existing.id() + " and " + region.id());
            }
        }
        byId.put(region.id(), region);
        worldRegions.add(region);
    }

    public synchronized Optional<ProtectedRegion> find(String id) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(id, "id")));
    }

    public synchronized Optional<ProtectedRegion> at(Location location) {
        Objects.requireNonNull(location, "location");
        if (location.getWorld() == null) {
            return Optional.empty();
        }
        return byWorld.getOrDefault(location.getWorld().getUID(), List.of()).stream()
                .filter(region -> region.bounds().contains(location))
                .findFirst();
    }

    public synchronized Optional<ProtectedRegion> at(Block block) {
        Objects.requireNonNull(block, "block");
        return byWorld.getOrDefault(block.getWorld().getUID(), List.of()).stream()
                .filter(region -> region.bounds().contains(block))
                .findFirst();
    }

    public synchronized List<ProtectedRegion> all() {
        return List.copyOf(byId.values());
    }
}
