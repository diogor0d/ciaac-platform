package com.ciaac.minecraft.minigames.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class RuntimeConfigurationLoaderTest {
    @Test
    void defaultTemplateKeepsEveryMandatorySafetyInvariantEnabled() {
        RuntimeConfiguration configuration = new RuntimeConfigurationLoader().load(defaultConfiguration());

        assertTrue(configuration.globalProblems().stream()
                .noneMatch(problem -> problem.code().equals("SAFETY_INVARIANT_DISABLED")));
    }

    @Test
    void anOperatorCannotSilentlyDisableARequiredIsolationFacet() {
        YamlConfiguration source = defaultConfiguration();
        source.set("progress-isolation.inventory", false);

        RuntimeConfiguration configuration = new RuntimeConfigurationLoader().load(source);

        assertFalse(configuration.canAttemptAdmission());
        assertTrue(configuration.globalProblems().stream().anyMatch(problem ->
                problem.code().equals("SAFETY_INVARIANT_DISABLED")
                        && problem.path().equals("progress-isolation.inventory")));
    }

    @Test
    void fullHeightRegionIsLoadedAndNonBooleanFlagFailsClosed() {
        YamlConfiguration source = defaultConfiguration();
        source.set("modules.arena.world.name", "arena-world");
        source.set("modules.arena.world.uuid", UUID.randomUUID().toString());
        source.set("modules.arena.regions.combat-floor.min", List.of(-418, 0, 6425));
        source.set("modules.arena.regions.combat-floor.max", List.of(-321, 100, 6470));
        source.set("modules.arena.regions.combat-floor.full-height", true);
        source.set("modules.arena.regions.spectator-benches.min", List.of(-430, 0, 6400));
        source.set("modules.arena.regions.spectator-benches.max", List.of(-310, 100, 6490));

        ModuleConfiguration arena = new RuntimeConfigurationLoader().load(source).modules().get(GameKey.ARENA);

        assertTrue(arena.problems().isEmpty());
        assertTrue(arena.regions().get("combat-floor").fullHeight());

        source.set("modules.arena.regions.combat-floor.full-height", "true");
        arena = new RuntimeConfigurationLoader().load(source).modules().get(GameKey.ARENA);

        assertFalse(arena.problems().isEmpty());
        assertTrue(arena.problems().stream().anyMatch(problem ->
                problem.path().equals("modules.arena.regions.combat-floor.full-height")));
        assertFalse(arena.regions().containsKey("combat-floor"));
    }

    private static YamlConfiguration defaultConfiguration() {
        InputStream stream = RuntimeConfigurationLoaderTest.class.getClassLoader()
                .getResourceAsStream("config.yml");
        if (stream == null) throw new IllegalStateException("config.yml test resource is missing");
        try (stream; InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not read config.yml test resource", failure);
        }
    }
}
