package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.hotpotato.HotPotatoConfig;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;

/** Resolved one-instance Hot Potato configuration. */
public record ResolvedHotPotatoConfiguration(
        World world,
        Map<String, Location> locations,
        Map<String, CuboidRegion> regions,
        HotPotatoConfig domain,
        List<Location> spawns,
        Duration initialFuse,
        Duration minimumFuse,
        Duration fuseReductionPerRound,
        Duration countdown,
        Duration tokenLifetime) implements ResolvedGameConfiguration {

    public ResolvedHotPotatoConfiguration {
        world = Objects.requireNonNull(world, "world");
        locations = ResolvedConfigurationSupport.locations(locations);
        regions = ResolvedConfigurationSupport.regions(regions);
        domain = Objects.requireNonNull(domain, "domain");
        spawns = ResolvedConfigurationSupport.locationList(spawns);
        initialFuse = ResolvedConfigurationSupport.positive(initialFuse, "initialFuse");
        minimumFuse = ResolvedConfigurationSupport.positive(minimumFuse, "minimumFuse");
        fuseReductionPerRound = ResolvedConfigurationSupport.nonNegative(fuseReductionPerRound, "fuseReductionPerRound");
        countdown = ResolvedConfigurationSupport.positive(countdown, "countdown");
        tokenLifetime = ResolvedConfigurationSupport.positive(tokenLifetime, "tokenLifetime");
    }

    @Override public GameKey game() { return GameKey.HOT_POTATO; }

    @Override public IsolationPolicy isolation() { return domain.isolation(); }
}
