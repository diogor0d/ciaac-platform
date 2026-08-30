package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.configuration.WorldReference;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;

/** Resolved state for one module; {@code admissionAllowed} is fail-closed. */
public record ResolvedModuleConfiguration(
        GameKey game,
        boolean enabled,
        boolean admissionAllowed,
        Optional<WorldReference> worldReference,
        Optional<World> world,
        Map<String, Location> locations,
        Map<String, CuboidRegion> regions,
        Optional<ResolvedGameConfiguration> gameConfiguration,
        List<ResolutionDiagnostic> diagnostics) {

    public ResolvedModuleConfiguration {
        game = Objects.requireNonNull(game, "game");
        worldReference = Objects.requireNonNull(worldReference, "worldReference");
        world = Objects.requireNonNull(world, "world");
        locations = ResolvedConfigurationSupport.locations(locations);
        regions = ResolvedConfigurationSupport.regions(regions);
        gameConfiguration = Objects.requireNonNull(gameConfiguration, "gameConfiguration");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (world.isPresent() && worldReference.isEmpty()) {
            throw new IllegalArgumentException("A resolved world needs its configured reference");
        }
        if (gameConfiguration.isPresent() && gameConfiguration.orElseThrow().game() != game) {
            throw new IllegalArgumentException("Resolved game configuration has the wrong key");
        }
        if (admissionAllowed && (!enabled || gameConfiguration.isEmpty() || world.isEmpty() || !diagnostics.isEmpty())) {
            throw new IllegalArgumentException("Admission cannot be allowed with unresolved state");
        }
    }

    public boolean closed() {
        return !admissionAllowed;
    }
}
