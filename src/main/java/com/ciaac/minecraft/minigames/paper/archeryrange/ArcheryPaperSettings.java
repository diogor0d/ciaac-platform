package com.ciaac.minecraft.minigames.paper.archeryrange;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;

/**
 * Immutable Paper-facing geometry and rules inputs for the archery range.
 *
 * <p>The six-argument constructor remains for source compatibility with the
 * first single-lane adapter. New configuration must use {@link #lanes()} and
 * provide one immutable lane definition per physical lane. The controller
 * validates the corresponding domain rules separately so a geometry-only
 * settings object cannot accidentally become a scoring authority.</p>
 */
public record ArcheryPaperSettings(
        boolean enabled,
        Map<Integer, LaneSettings> lanes,
        int shotCount,
        Duration tokenTtl,
        Duration attemptTimeout,
        Material bowMaterial,
        Map<String, Integer> scoreBands) {

    public ArcheryPaperSettings {
        Objects.requireNonNull(lanes, "lanes");
        if (lanes.isEmpty() || lanes.size() > 128) {
            throw new IllegalArgumentException("archery lanes must contain 1-128 entries");
        }
        LinkedHashMap<Integer, LaneSettings> copied = new LinkedHashMap<>();
        Set<String> regionIds = new HashSet<>();
        Set<String> targetIds = new HashSet<>();
        lanes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Integer key = Objects.requireNonNull(entry.getKey(), "lane id");
                    LaneSettings lane = Objects.requireNonNull(entry.getValue(), "lane");
                    if (key.intValue() != lane.id()) {
                        throw new IllegalArgumentException("lane map key must match lane id");
                    }
                    if (!regionIds.add(lane.regionId())) {
                        throw new IllegalArgumentException("each archery lane needs its own region");
                    }
                    if (!targetIds.add(lane.targetId())) {
                        throw new IllegalArgumentException("each archery lane needs its own target");
                    }
                    if (copied.put(key, lane) != null) {
                        throw new IllegalArgumentException("duplicate archery lane");
                    }
                });
        lanes = Map.copyOf(copied);

        if (shotCount < 1 || shotCount > 128) {
            throw new IllegalArgumentException("shotCount must be between 1 and 128");
        }
        tokenTtl = positive(tokenTtl, "tokenTtl");
        attemptTimeout = positive(attemptTimeout, "attemptTimeout");
        bowMaterial = Objects.requireNonNull(bowMaterial, "bowMaterial");
        if (bowMaterial != Material.BOW) {
            throw new IllegalArgumentException("bowMaterial must be BOW for native arrow shots");
        }
        Objects.requireNonNull(scoreBands, "scoreBands");
        LinkedHashMap<String, Integer> scores = new LinkedHashMap<>();
        scoreBands.forEach((key, value) -> {
            if (key == null || !key.matches("[a-z][a-z0-9_.-]{0,31}")) {
                throw new IllegalArgumentException("score band id is invalid");
            }
            if (value == null || value < 0 || value > 100) {
                throw new IllegalArgumentException("score band value is invalid");
            }
            if (scores.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate score band");
            }
        });
        scoreBands = Map.copyOf(scores);
    }

    /** Compatibility constructor for the original one-lane adapter. */
    public ArcheryPaperSettings(
            boolean enabled,
            String regionId,
            int laneId,
            Location spawn,
            int shotCount,
            Duration tokenTtl) {
        this(enabled,
                Map.of(laneId, new LaneSettings(laneId, regionId, spawn, "target-" + laneId)),
                shotCount,
                tokenTtl,
                tokenTtl,
                Material.BOW,
                Map.of());
    }

    /** Creates a disabled compatibility configuration for a known lane. */
    public static ArcheryPaperSettings disabled(
            String region, int lane, Location spawn, int shots) {
        return new ArcheryPaperSettings(false, region, lane, spawn, shots, Duration.ofMinutes(10));
    }

    /**
     * Constructs a multi-lane settings object in deterministic lane order.
     * Callers should pass the server-resolved score bands and bow material;
     * empty score bands are accepted only for the legacy constructor path and
     * are rejected by the preferred multi-lane controller constructor.
     */
    public static ArcheryPaperSettings multi(
            boolean enabled,
            List<LaneSettings> laneDefinitions,
            int shotCount,
            Duration tokenTtl,
            Duration attemptTimeout,
            Material bowMaterial,
            Map<String, Integer> scoreBands) {
        Objects.requireNonNull(laneDefinitions, "laneDefinitions");
        LinkedHashMap<Integer, LaneSettings> lanes = new LinkedHashMap<>();
        laneDefinitions.stream()
                .sorted(Comparator.comparingInt(LaneSettings::id))
                .forEach(lane -> {
                    LaneSettings previous = lanes.put(
                            Objects.requireNonNull(lane, "lane").id(), lane);
                    if (previous != null) {
                        throw new IllegalArgumentException("duplicate archery lane");
                    }
                });
        return new ArcheryPaperSettings(
                enabled, lanes, shotCount, tokenTtl, attemptTimeout, bowMaterial, scoreBands);
    }

    /** First lane in deterministic order, retained for old adapter callers. */
    public LaneSettings firstLane() {
        return lanes.values().stream()
                .min(Comparator.comparingInt(LaneSettings::id))
                .orElseThrow();
    }

    public Optional<LaneSettings> lane(int laneId) {
        return Optional.ofNullable(lanes.get(laneId));
    }

    /** Legacy single-lane accessors. */
    public String regionId() { return firstLane().regionId(); }
    public int laneId() { return firstLane().id(); }
    public Location spawn() { return firstLane().spawn().clone(); }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /** One physical lane and its server-owned target identity. */
    public record LaneSettings(int id, String regionId, Location spawn, String targetId) {
        public LaneSettings {
            if (id < 0 || id > 1024) {
                throw new IllegalArgumentException("lane id is invalid");
            }
            if (regionId == null
                    || !regionId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
                throw new IllegalArgumentException("regionId is invalid");
            }
            spawn = Objects.requireNonNull(spawn, "spawn").clone();
            if (spawn.getWorld() == null) {
                throw new IllegalArgumentException("spawn needs a world");
            }
            if (targetId == null
                    || !targetId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")) {
                throw new IllegalArgumentException("targetId is invalid");
            }
        }

        @Override public Location spawn() { return spawn.clone(); }
    }
}
