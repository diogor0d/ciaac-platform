package com.ciaac.minecraft.minigames.paper.elytrarings;

import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsConfig;
import com.ciaac.minecraft.minigames.elytrarings.RingCheckpoint;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
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

    /**
     * Returns the earliest segment fraction that enters the ordered ring.
     * Exact regions use continuous block-space bounds [min, max + 1), so a
     * tangent that only touches an open maximum face does not count. If the
     * segment starts on an open maximum face and moves inward, the returned
     * fraction is the boundary infimum (the point at that exact fraction is
     * still outside; every immediately later point is inside). Legacy fixtures
     * without resolved regions intersect a sphere of {@code ringRadius} around
     * the configured checkpoint. Zero-length segments only hit from inside.
     */
    public OptionalDouble ringCrossing(int oneBasedOrder, Location from, Location to) {
        if (from == null || to == null || oneBasedOrder < 1
                || oneBasedOrder > config.course().rings().size() || world == null
                || !sameCourseWorld(from.getWorld()) || !sameCourseWorld(to.getWorld())
                || !sameWorld(from.getWorld(), to.getWorld())
                || !finite(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ())) {
            return OptionalDouble.empty();
        }
        if (!ringRegions.isEmpty()) {
            CuboidRegion region = ringRegions.get(oneBasedOrder - 1);
            return intersectCuboid(region, from, to);
        }
        RingCheckpoint ring = config.course().rings().get(oneBasedOrder - 1);
        return intersectSphere(ring, from, to);
    }

    private OptionalDouble intersectCuboid(CuboidRegion region, Location from, Location to) {
        double[] start = {from.getX(), from.getY(), from.getZ()};
        double[] delta = {to.getX() - start[0], to.getY() - start[1], to.getZ() - start[2]};
        double[] min = {region.minX(), region.minY(), region.minZ()};
        double[] max = {(double) region.maxX() + 1.0, (double) region.maxY() + 1.0,
                (double) region.maxZ() + 1.0};
        if (!finite(delta[0], delta[1], delta[2])) return OptionalDouble.empty();
        double enter = 0.0;
        double exit = 1.0;
        for (int axis = 0; axis < 3; axis++) {
            if (delta[axis] == 0.0) {
                if (start[axis] < min[axis] || start[axis] >= max[axis]) return OptionalDouble.empty();
                continue;
            }
            double first;
            double second;
            if (delta[axis] > 0.0) {
                first = (min[axis] - start[axis]) / delta[axis];
                second = (max[axis] - start[axis]) / delta[axis];
            } else {
                first = (max[axis] - start[axis]) / delta[axis];
                second = (min[axis] - start[axis]) / delta[axis];
            }
            enter = Math.max(enter, first);
            exit = Math.min(exit, second);
            if (enter > exit) return OptionalDouble.empty();
        }
        if (enter < exit) return OptionalDouble.of(enter);
        if (enter < 0.0 || enter > 1.0) return OptionalDouble.empty();
        for (int axis = 0; axis < 3; axis++) {
            double point = start[axis] + delta[axis] * enter;
            if (point < min[axis] || point >= max[axis]) return OptionalDouble.empty();
        }
        return OptionalDouble.of(enter);
    }

    private OptionalDouble intersectSphere(RingCheckpoint ring, Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double mx = from.getX() - ring.x();
        double my = from.getY() - ring.y();
        double mz = from.getZ() - ring.z();
        double a = dx * dx + dy * dy + dz * dz;
        double c = mx * mx + my * my + mz * mz - ringRadius * ringRadius;
        if (!finite(dx, dy, dz, mx, my, mz, a, c)) return OptionalDouble.empty();
        if (a == 0.0) return c <= 0.0 ? OptionalDouble.of(0.0) : OptionalDouble.empty();
        if (c <= 0.0) return OptionalDouble.of(0.0);
        double b = 2.0 * (mx * dx + my * dy + mz * dz);
        double discriminant = b * b - 4.0 * a * c;
        if (!finite(b, discriminant) || discriminant < 0.0) return OptionalDouble.empty();
        double fraction = (-b - Math.sqrt(discriminant)) / (2.0 * a);
        return fraction >= 0.0 && fraction <= 1.0
                ? OptionalDouble.of(fraction) : OptionalDouble.empty();
    }

    private boolean sameCourseWorld(World candidate) {
        if (candidate == null) return false;
        String configuredName = world.getName();
        String candidateName = candidate.getName();
        return configuredName != null && candidateName != null
                && world.getUID().equals(candidate.getUID()) && configuredName.equals(candidateName);
    }

    private static boolean sameWorld(World left, World right) {
        if (left == null || right == null) return false;
        String leftName = left.getName();
        String rightName = right.getName();
        return leftName != null && rightName != null
                && left.getUID().equals(right.getUID()) && leftName.equals(rightName);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
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
