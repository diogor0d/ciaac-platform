package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.elytrarings.ElytraCourseRevision;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsConfig;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;

/** Dedicated-world ordered-ring configuration with explicit adapter targets. */
public record ResolvedElytraRingsConfiguration(
        World world,
        Map<String, Location> locations,
        Map<String, CuboidRegion> regions,
        ElytraRingsConfig domain,
        ElytraCourseRevision course,
        String worldTemplateMarker,
        List<String> ringOrder,
        Map<String, CuboidRegion> ringRegions,
        int preloadRadiusChunks,
        int fireworkRockets,
        boolean allowRockets,
        int concurrentRunners,
        double ringRadius,
        TemplateResolution ringTargetInput) implements ResolvedGameConfiguration {

    public ResolvedElytraRingsConfiguration {
        world = Objects.requireNonNull(world, "world");
        locations = ResolvedConfigurationSupport.locations(locations);
        regions = ResolvedConfigurationSupport.regions(regions);
        domain = Objects.requireNonNull(domain, "domain");
        course = Objects.requireNonNull(course, "course");
        worldTemplateMarker = ResolvedConfigurationSupport.text(worldTemplateMarker, "worldTemplateMarker", 128);
        ringOrder = ResolvedConfigurationSupport.ids(ringOrder, "ringOrder", 256);
        ringRegions = ResolvedConfigurationSupport.regions(ringRegions);
        if (preloadRadiusChunks < 0 || preloadRadiusChunks > 8) throw new IllegalArgumentException("preloadRadiusChunks is invalid");
        if (fireworkRockets < 0 || fireworkRockets > 64) throw new IllegalArgumentException("fireworkRockets is invalid");
        if (concurrentRunners < 1 || concurrentRunners > 64) throw new IllegalArgumentException("concurrentRunners is invalid");
        if (concurrentRunners != 1) throw new IllegalArgumentException("only one concurrent Elytra runner is supported");
        if (!Double.isFinite(ringRadius) || ringRadius <= 0 || ringRadius > 16) throw new IllegalArgumentException("ringRadius is invalid");
        ringTargetInput = Objects.requireNonNull(ringTargetInput, "ringTargetInput");
    }

    @Override public GameKey game() { return GameKey.ELYTRA_RINGS; }

    @Override public IsolationPolicy isolation() { return domain.isolation(); }
}
