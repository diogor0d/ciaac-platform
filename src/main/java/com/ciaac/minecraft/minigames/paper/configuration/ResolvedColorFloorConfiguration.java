package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.colorfloor.ColorFloorConfig;
import com.ciaac.minecraft.minigames.colorfloor.FloorColor;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;

/** Resolved Color Floor palette and immutable-template hand-off. */
public record ResolvedColorFloorConfiguration(
        World world,
        Map<String, Location> locations,
        Map<String, CuboidRegion> regions,
        String rulesetRevision,
        ColorFloorConfig domain,
        List<FloorColor> palette,
        Duration announceDuration,
        Duration unsafeDuration,
        String restoreStrategy,
        TemplateResolution floorTemplate) implements ResolvedGameConfiguration {

    public ResolvedColorFloorConfiguration {
        world = Objects.requireNonNull(world, "world");
        locations = ResolvedConfigurationSupport.locations(locations);
        regions = ResolvedConfigurationSupport.regions(regions);
        rulesetRevision = ResolvedConfigurationSupport.text(rulesetRevision, "rulesetRevision", 32);
        domain = Objects.requireNonNull(domain, "domain");
        palette = List.copyOf(Objects.requireNonNull(palette, "palette"));
        if (palette.size() < 2 || palette.size() > 16) throw new IllegalArgumentException("palette is invalid");
        announceDuration = ResolvedConfigurationSupport.positive(announceDuration, "announceDuration");
        unsafeDuration = ResolvedConfigurationSupport.positive(unsafeDuration, "unsafeDuration");
        restoreStrategy = ResolvedConfigurationSupport.text(restoreStrategy, "restoreStrategy", 64);
        floorTemplate = Objects.requireNonNull(floorTemplate, "floorTemplate");
    }

    @Override public GameKey game() { return GameKey.COLOR_FLOOR; }

    @Override public IsolationPolicy isolation() { return domain.isolation(); }
}
