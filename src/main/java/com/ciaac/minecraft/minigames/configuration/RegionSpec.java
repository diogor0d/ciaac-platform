package com.ciaac.minecraft.minigames.configuration;

import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.util.Objects;

public record RegionSpec(
        WorldReference world,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ) {

    public RegionSpec {
        Objects.requireNonNull(world, "world");
        new CuboidRegion(world.worldId(), minX, minY, minZ, maxX, maxY, maxZ);
    }

    public CuboidRegion toRegion() {
        return new CuboidRegion(world.worldId(), minX, minY, minZ, maxX, maxY, maxZ);
    }
}
