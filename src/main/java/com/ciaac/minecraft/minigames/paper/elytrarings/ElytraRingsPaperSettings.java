package com.ciaac.minecraft.minigames.paper.elytrarings;

import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsConfig;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.util.List;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;

/** Dedicated-world-only Bukkit settings for one ordered Elytra time trial. */
public record ElytraRingsPaperSettings(
        boolean enabled,
        ElytraRingsConfig config,
        World world,
        String participantRegionId,
        Location start,
        double ringRadius,
        int rocketCount,
        int preloadRadiusChunks,
        List<CuboidRegion> ringRegions) {

    public ElytraRingsPaperSettings {
        config = Objects.requireNonNull(config, "config");
        if (participantRegionId == null
                || !participantRegionId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("participantRegionId is invalid");
        }
        if (!Double.isFinite(ringRadius) || ringRadius <= 0 || ringRadius > 16) {
            throw new IllegalArgumentException("ringRadius is invalid");
        }
        if (rocketCount < 0 || rocketCount > 64) {
            throw new IllegalArgumentException("rocketCount is invalid");
        }
        if (preloadRadiusChunks < 0 || preloadRadiusChunks > 8) {
            throw new IllegalArgumentException("preloadRadiusChunks is invalid");
        }
        ringRegions = List.copyOf(Objects.requireNonNull(ringRegions, "ringRegions"));
        if (enabled) {
            world = Objects.requireNonNull(world, "world");
            start = copyInWorld(start, world);
            if (!config.course().worldId().equals(world.getUID().toString())) {
                throw new IllegalArgumentException("course must identify the dedicated world UUID");
            }
            if (!ringRegions.isEmpty() && ringRegions.size() != config.course().rings().size()) {
                throw new IllegalArgumentException("ring region count must match the ordered course");
            }
            for (CuboidRegion region : ringRegions) {
                if (!region.worldId().equals(world.getUID())) {
                    throw new IllegalArgumentException("ring region belongs to another world");
                }
            }
        } else {
            start = start == null ? null : start.clone();
        }
    }

    /** Compatibility constructor for fixtures that predate exact ring regions. */
    public ElytraRingsPaperSettings(
            boolean enabled,
            ElytraRingsConfig config,
            World world,
            String participantRegionId,
            Location start,
            double ringRadius,
            int rocketCount,
            int preloadRadiusChunks) {
        this(enabled, config, world, participantRegionId, start, ringRadius,
                rocketCount, preloadRadiusChunks, List.of());
    }

    /** Compatibility constructor for older fixtures. */
    public ElytraRingsPaperSettings(
            boolean enabled,
            ElytraRingsConfig config,
            World world,
            String participantRegionId,
            Location start,
            double ringRadius,
            boolean grantRockets) {
        this(enabled, config, world, participantRegionId, start, ringRadius,
                grantRockets ? 16 : 0, 0, List.of());
    }

    public boolean grantRockets() {
        return rocketCount > 0;
    }

    /**
     * Production configuration uses the exact ordered cuboid. The radius path
     * remains only for source-compatible fixtures without resolved regions.
     */
    public boolean reachedRing(int oneBasedOrder, Location location) {
        Objects.requireNonNull(location, "location");
        if (oneBasedOrder < 1 || oneBasedOrder > config.course().rings().size()) return false;
        if (!ringRegions.isEmpty()) return ringRegions.get(oneBasedOrder - 1).contains(location);
        var ring = config.course().rings().get(oneBasedOrder - 1);
        if (location.getWorld() != world) return false;
        Location centre = new Location(world, ring.x(), ring.y(), ring.z());
        return location.distanceSquared(centre) <= ringRadius * ringRadius;
    }

    public static ElytraRingsPaperSettings disabled(ElytraRingsConfig config, String regionId) {
        return new ElytraRingsPaperSettings(
                false, config, null, regionId, null, 3.0, 0, 0, List.of());
    }

    private static Location copyInWorld(Location value, World world) {
        Objects.requireNonNull(value, "start");
        if (value.getWorld() != world) throw new IllegalArgumentException("start belongs to another world");
        return value.clone();
    }
}
