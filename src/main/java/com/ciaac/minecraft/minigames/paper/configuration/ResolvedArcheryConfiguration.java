package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.archeryrange.ArcheryConfig;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/** Resolved lane and score-band inputs for the spawn-safezone range. */
public record ResolvedArcheryConfiguration(
        World world,
        Map<String, Location> locations,
        Map<String, CuboidRegion> regions,
        String rulesetRevision,
        IsolationPolicy isolation,
        Map<Integer, LaneDefinition> lanes,
        Map<String, Integer> scoreBands,
        int shotsPerAttempt,
        Duration attemptTimeout,
        Material bowMaterial) implements ResolvedGameConfiguration {

    public ResolvedArcheryConfiguration {
        world = Objects.requireNonNull(world, "world");
        locations = ResolvedConfigurationSupport.locations(locations);
        regions = ResolvedConfigurationSupport.regions(regions);
        rulesetRevision = ResolvedConfigurationSupport.text(rulesetRevision, "rulesetRevision", 32);
        isolation = Objects.requireNonNull(isolation, "isolation");
        lanes = Map.copyOf(Objects.requireNonNull(lanes, "lanes"));
        scoreBands = Map.copyOf(Objects.requireNonNull(scoreBands, "scoreBands"));
        if (lanes.isEmpty()) throw new IllegalArgumentException("at least one archery lane is required");
        if (shotsPerAttempt < 1 || shotsPerAttempt > 128) throw new IllegalArgumentException("shotsPerAttempt is invalid");
        attemptTimeout = ResolvedConfigurationSupport.positive(attemptTimeout, "attemptTimeout");
        bowMaterial = Objects.requireNonNull(bowMaterial, "bowMaterial");
    }

    @Override public GameKey game() { return GameKey.ARCHERY_RANGE; }

    public record LaneDefinition(int id, String regionId, Location spawn, String targetId,
                                 TemplateResolution targetInput, ArcheryConfig domain) {
        public LaneDefinition {
            if (id < 0 || id > 1024) throw new IllegalArgumentException("lane id is invalid");
            regionId = ResolvedConfigurationSupport.text(regionId, "regionId", 64);
            spawn = Objects.requireNonNull(spawn, "spawn").clone();
            targetId = ResolvedConfigurationSupport.text(targetId, "targetId", 64);
            targetInput = Objects.requireNonNull(targetInput, "targetInput");
            domain = Objects.requireNonNull(domain, "domain");
        }
    }
}
