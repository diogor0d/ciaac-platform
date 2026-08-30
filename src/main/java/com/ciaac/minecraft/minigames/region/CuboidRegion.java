package com.ciaac.minecraft.minigames.region;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.Block;

/** Immutable inclusive block-space cuboid bound to one world identity. */
public record CuboidRegion(
        UUID worldId,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ) {

    public CuboidRegion {
        Objects.requireNonNull(worldId, "worldId");
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Cuboid minimum coordinates must not exceed maxima");
        }
        long volume = (long) (maxX - minX + 1)
                * (long) (maxY - minY + 1)
                * (long) (maxZ - minZ + 1);
        if (volume <= 0 || volume > 100_000_000L) {
            throw new IllegalArgumentException("Cuboid volume is invalid or unreasonably large");
        }
    }

    public boolean contains(Location location) {
        Objects.requireNonNull(location, "location");
        return location.getWorld() != null
                && worldId.equals(location.getWorld().getUID())
                && contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public boolean contains(Block block) {
        Objects.requireNonNull(block, "block");
        return worldId.equals(block.getWorld().getUID())
                && contains(block.getX(), block.getY(), block.getZ());
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean intersects(CuboidRegion other) {
        Objects.requireNonNull(other, "other");
        return worldId.equals(other.worldId)
                && minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
