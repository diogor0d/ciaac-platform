package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.anvildodge.AnvilDodgeConfig;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;

/** Resolved deterministic wave inputs; hazard entities remain adapter-owned. */
public record ResolvedAnvilDodgeConfiguration(
        World world,
        Map<String, Location> locations,
        Map<String, CuboidRegion> regions,
        String rulesetRevision,
        AnvilDodgeConfig domain,
        int hazardsPerWaveStart,
        int hazardsPerWaveIncrement,
        int floorWidth,
        int floorDepth,
        TemplateResolution hazardInput) implements ResolvedGameConfiguration {

    public ResolvedAnvilDodgeConfiguration {
        world = Objects.requireNonNull(world, "world");
        locations = ResolvedConfigurationSupport.locations(locations);
        regions = ResolvedConfigurationSupport.regions(regions);
        rulesetRevision = ResolvedConfigurationSupport.text(rulesetRevision, "rulesetRevision", 32);
        domain = Objects.requireNonNull(domain, "domain");
        if (hazardsPerWaveStart < 1 || hazardsPerWaveStart > 4096 || hazardsPerWaveIncrement < 0 || hazardsPerWaveIncrement > 4096) {
            throw new IllegalArgumentException("hazard counts are invalid");
        }
        if (floorWidth < 1 || floorDepth < 1 || floorWidth * (long) floorDepth > 4096) {
            throw new IllegalArgumentException("floor geometry is invalid");
        }
        hazardInput = Objects.requireNonNull(hazardInput, "hazardInput");
    }

    @Override public GameKey game() { return GameKey.ANVIL_DODGE; }

    @Override public IsolationPolicy isolation() { return domain.isolation(); }
}
