package com.ciaac.minecraft.minigames.configuration;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ModuleConfiguration(
        GameKey game,
        boolean enabled,
        Optional<WorldReference> world,
        Map<String, LocationSpec> locations,
        Map<String, RegionSpec> regions,
        ConfigValues values,
        List<ConfigurationProblem> problems) {

    public ModuleConfiguration {
        Objects.requireNonNull(game, "game");
        world = Objects.requireNonNull(world, "world");
        locations = Map.copyOf(Objects.requireNonNull(locations, "locations"));
        regions = Map.copyOf(Objects.requireNonNull(regions, "regions"));
        Objects.requireNonNull(values, "values");
        problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
    }

    public boolean readyForSourceWiring() {
        return enabled && world.isPresent() && problems.isEmpty();
    }
}
