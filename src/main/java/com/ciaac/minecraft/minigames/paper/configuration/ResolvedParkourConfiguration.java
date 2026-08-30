package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.checkpointparkour.ParkourConfig;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;

/** Resolved ordered checkpoint course; checkpoint IDs remain stable adapter inputs. */
public record ResolvedParkourConfiguration(
        World world,
        Map<String, Location> locations,
        Map<String, CuboidRegion> regions,
        ParkourConfig domain,
        IsolationPolicy isolation,
        List<String> checkpointOrder,
        Map<String, CuboidRegion> checkpointRegions,
        int concurrentRunners,
        boolean hideOtherRunners) implements ResolvedGameConfiguration {

    public ResolvedParkourConfiguration {
        world = Objects.requireNonNull(world, "world");
        locations = ResolvedConfigurationSupport.locations(locations);
        regions = ResolvedConfigurationSupport.regions(regions);
        domain = Objects.requireNonNull(domain, "domain");
        isolation = Objects.requireNonNull(isolation, "isolation");
        checkpointOrder = ResolvedConfigurationSupport.ids(checkpointOrder, "checkpointOrder", 256);
        checkpointRegions = ResolvedConfigurationSupport.regions(checkpointRegions);
        if (concurrentRunners < 1 || concurrentRunners > 64) throw new IllegalArgumentException("concurrentRunners is invalid");
        for (String checkpointId : checkpointRegions.keySet()) {
            if (!checkpointOrder.contains(checkpointId)) {
                throw new IllegalArgumentException("checkpoint region is not in the ordered course");
            }
        }
    }

    @Override public GameKey game() { return GameKey.CHECKPOINT_PARKOUR; }
}
