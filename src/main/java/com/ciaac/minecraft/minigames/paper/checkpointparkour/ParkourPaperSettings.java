package com.ciaac.minecraft.minigames.paper.checkpointparkour;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.bukkit.Location;

public record ParkourPaperSettings(
        boolean enabled,
        String regionId,
        Location gate,
        List<Location> checkpoints,
        Duration tokenTtl,
        int maximumConcurrentRuns,
        boolean hideOtherRunners) {

    public ParkourPaperSettings {
        if (regionId == null || !regionId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid regionId");
        }
        gate = require(gate, "gate");
        checkpoints = copy(checkpoints);
        if (checkpoints.isEmpty() || tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalArgumentException("invalid parkour settings");
        }
        if (maximumConcurrentRuns < 1 || maximumConcurrentRuns > 64) {
            throw new IllegalArgumentException("maximumConcurrentRuns must be between 1 and 64");
        }
    }

    public ParkourPaperSettings(
            boolean enabled,
            String regionId,
            Location gate,
            List<Location> checkpoints,
            Duration tokenTtl) {
        this(enabled, regionId, gate, checkpoints, tokenTtl, 8, false);
    }

    public static ParkourPaperSettings disabled(
            String region, Location gate, List<Location> checkpoints) {
        return new ParkourPaperSettings(
                false, region, gate, checkpoints, Duration.ofMinutes(10), 8, false);
    }

    @Override public Location gate() { return gate.clone(); }
    @Override public List<Location> checkpoints() {
        return checkpoints.stream().map(Location::clone).toList();
    }

    private static Location require(Location location, String name) {
        Objects.requireNonNull(location, name);
        if (location.getWorld() == null) throw new IllegalArgumentException(name + " needs a world");
        return location.clone();
    }

    private static List<Location> copy(List<Location> values) {
        Objects.requireNonNull(values, "checkpoints");
        return values.stream().map(location -> require(location, "checkpoint")).toList();
    }
}
