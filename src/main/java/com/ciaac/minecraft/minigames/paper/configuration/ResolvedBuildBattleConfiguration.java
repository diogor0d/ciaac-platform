package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.buildbattle.BuildBattleConfig;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleThemePool;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleTiePolicy;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleVotingCompletionPolicy;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;

/** Resolved Build Battle rules; reset/template work remains adapter-owned. */
public record ResolvedBuildBattleConfiguration(
        World world,
        Map<String, Location> locations,
        Map<String, CuboidRegion> regions,
        String rulesetRevision,
        IsolationPolicy isolation,
        BuildBattleConfig domain,
        BuildBattleThemePool themes,
        String worldTemplateMarker,
        Map<String, PlotDefinition> plots,
        Duration queueDuration,
        Duration themeVoteDuration,
        Duration buildDuration,
        Duration voteDuration,
        int plotSize,
        int plotSpacing,
        int secondsPerPlot,
        TemplateResolution resetTemplate,
        Duration resetTimeout) implements ResolvedGameConfiguration {

    public ResolvedBuildBattleConfiguration {
        world = Objects.requireNonNull(world, "world");
        locations = ResolvedConfigurationSupport.locations(locations);
        regions = ResolvedConfigurationSupport.regions(regions);
        rulesetRevision = ResolvedConfigurationSupport.text(rulesetRevision, "rulesetRevision", 32);
        isolation = Objects.requireNonNull(isolation, "isolation");
        domain = Objects.requireNonNull(domain, "domain");
        themes = Objects.requireNonNull(themes, "themes");
        worldTemplateMarker = ResolvedConfigurationSupport.text(worldTemplateMarker, "worldTemplateMarker", 128);
        plots = Map.copyOf(Objects.requireNonNull(plots, "plots"));
        queueDuration = ResolvedConfigurationSupport.positive(queueDuration, "queueDuration");
        themeVoteDuration = ResolvedConfigurationSupport.positive(themeVoteDuration, "themeVoteDuration");
        buildDuration = ResolvedConfigurationSupport.positive(buildDuration, "buildDuration");
        voteDuration = ResolvedConfigurationSupport.positive(voteDuration, "voteDuration");
        if (plotSize < 3 || plotSize > 128 || plotSpacing < 0 || plotSpacing > 64) {
            throw new IllegalArgumentException("plot geometry is invalid");
        }
        if (secondsPerPlot < 1 || secondsPerPlot > 600) throw new IllegalArgumentException("secondsPerPlot is invalid");
        resetTemplate = Objects.requireNonNull(resetTemplate, "resetTemplate");
        resetTimeout = ResolvedConfigurationSupport.positive(resetTimeout, "resetTimeout");
    }

    @Override public GameKey game() { return GameKey.BUILD_BATTLE; }

    public record PlotDefinition(String id, String regionId, Location spawn, TemplateResolution resetInput) {
        public PlotDefinition {
            id = ResolvedConfigurationSupport.text(id, "id", 64);
            regionId = ResolvedConfigurationSupport.text(regionId, "regionId", 64);
            spawn = Objects.requireNonNull(spawn, "spawn").clone();
            resetInput = Objects.requireNonNull(resetInput, "resetInput");
        }
    }
}
