package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;

/** Common immutable contract implemented by each per-game resolved record. */
public interface ResolvedGameConfiguration {
    GameKey game();

    World world();

    Map<String, Location> locations();

    Map<String, CuboidRegion> regions();

    /** Every resolved game carries the full no-progress facet contract. */
    IsolationPolicy isolation();
}
