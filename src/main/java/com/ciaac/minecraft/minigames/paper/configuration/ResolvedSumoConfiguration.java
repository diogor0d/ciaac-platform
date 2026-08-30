package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.knockbacksumo.SumoConfig;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/** Resolved one-instance Knockback Sumo rules and platform geometry. */
public record ResolvedSumoConfiguration(
        World world,
        Map<String, Location> locations,
        Map<String, CuboidRegion> regions,
        SumoConfig domain,
        IsolationPolicy isolation,
        Material knockbackItem,
        int knockbackLevel,
        int fallThresholdY) implements ResolvedGameConfiguration {

    public ResolvedSumoConfiguration {
        world = Objects.requireNonNull(world, "world");
        locations = ResolvedConfigurationSupport.locations(locations);
        regions = ResolvedConfigurationSupport.regions(regions);
        domain = Objects.requireNonNull(domain, "domain");
        isolation = Objects.requireNonNull(isolation, "isolation");
        knockbackItem = Objects.requireNonNull(knockbackItem, "knockbackItem");
        if (knockbackLevel < 1 || knockbackLevel > 10) throw new IllegalArgumentException("knockbackLevel is invalid");
        if (fallThresholdY < -64 || fallThresholdY > 320) throw new IllegalArgumentException("fallThresholdY is invalid");
    }

    @Override public GameKey game() { return GameKey.KNOCKBACK_SUMO; }
}
