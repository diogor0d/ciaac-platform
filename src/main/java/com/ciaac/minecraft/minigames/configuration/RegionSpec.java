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
        int maxZ,
        boolean fullHeight) {

    public RegionSpec(WorldReference world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this(world, minX, minY, minZ, maxX, maxY, maxZ, false);
    }

    public RegionSpec {
        Objects.requireNonNull(world, "world");
        if (fullHeight) {
            if (minY > maxY) throw new IllegalArgumentException("Cuboid minimum coordinates must not exceed maxima");
            new CuboidRegion(world.worldId(), minX, minY, minZ, maxX, minY, maxZ);
        } else {
            new CuboidRegion(world.worldId(), minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    public CuboidRegion toRegion() {
        if (fullHeight) {
            throw new IllegalStateException("Full-height region resolution requires world height bounds");
        }
        return new CuboidRegion(world.worldId(), minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Resolves full-height regions using Paper's inclusive minimum and exclusive maximum heights. */
    public CuboidRegion toRegion(int worldMinHeight, int worldMaxHeightExclusive) {
        if (!fullHeight) return toRegion();
        if (worldMaxHeightExclusive <= worldMinHeight) {
            throw new IllegalArgumentException("World maximum height must exceed minimum height");
        }
        return new CuboidRegion(world.worldId(), minX, worldMinHeight, minZ,
                maxX, worldMaxHeightExclusive - 1, maxZ);
    }
}
