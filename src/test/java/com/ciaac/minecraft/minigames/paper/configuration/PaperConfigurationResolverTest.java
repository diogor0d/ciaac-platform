package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.configuration.AnnouncementConfiguration;
import com.ciaac.minecraft.minigames.configuration.ConfigValues;
import com.ciaac.minecraft.minigames.configuration.ModuleConfiguration;
import com.ciaac.minecraft.minigames.configuration.RegionSpec;
import com.ciaac.minecraft.minigames.configuration.RuntimeConfiguration;
import com.ciaac.minecraft.minigames.configuration.RuntimeConfigurationLoader;
import com.ciaac.minecraft.minigames.configuration.WorldReference;
import com.ciaac.minecraft.minigames.core.GameKey;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperConfigurationResolverTest {
    private static final UUID WORLD_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void disabledModulesAndGlobalAdmissionRemainClosed() {
        RuntimeConfiguration source = runtime(false, Map.of());
        ConfigurationResolutionResult result = new PaperConfigurationResolver().resolve(source, server(world("spawn", WORLD_ID), WORLD_ID));

        assertFalse(result.canAttemptAdmission());
        assertEquals(GameKey.values().length, result.modules().size());
        assertTrue(result.modules().values().stream().allMatch(ResolvedModuleConfiguration::closed));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("GLOBAL_ADMISSION_DISABLED")));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("MODULE_DISABLED")));
    }

    @Test
    void shippedDisabledDefaultsContainNoUnknownModuleKeys() {
        RuntimeConfiguration source = new RuntimeConfigurationLoader().load(defaultConfiguration());

        ConfigurationResolutionResult result = new PaperConfigurationResolver().resolve(
                source, server(world("spawn", WORLD_ID), WORLD_ID));

        assertTrue(result.diagnostics().stream().noneMatch(d -> d.code().equals("UNKNOWN_KEY")));
    }

    @Test
    void exactLoadedWorldIdentityAndSpawnPlacementPermitOnlyValidatedSumo() {
        WorldReference reference = new WorldReference("spawn", WORLD_ID);
        Map<String, Object> values = Map.of(
                "enabled", true,
                "minimum-players", 2,
                "maximum-players", 2,
                "best-of-rounds", 3,
                "round-timeout-seconds", 90,
                "fall-threshold-y", 0,
                "knockback-item-material", "STICK",
                "knockback-level", 2);
        ModuleConfiguration sumo = new ModuleConfiguration(
                GameKey.KNOCKBACK_SUMO, true, Optional.of(reference),
                Map.of(
                        "side-a", new com.ciaac.minecraft.minigames.configuration.LocationSpec(reference, 0, 1, 0, 0, 0),
                        "side-b", new com.ciaac.minecraft.minigames.configuration.LocationSpec(reference, 2, 1, 0, 0, 0),
                        "exit", new com.ciaac.minecraft.minigames.configuration.LocationSpec(reference, 0, 1, 0, 0, 0)),
                Map.of(
                        "platform", new RegionSpec(reference, -5, 0, -5, 5, 10, 5),
                        "boundary", new RegionSpec(reference, -10, -5, -10, 10, 20, 10)),
                new ConfigValues(values), List.of());

        EnumMap<GameKey, ModuleConfiguration> modules = disabledModules();
        modules.put(GameKey.KNOCKBACK_SUMO, sumo);
        RuntimeConfiguration source = runtime(true, modules);
        World loaded = world("spawn", WORLD_ID);
        ConfigurationResolutionResult result = new PaperConfigurationResolver().resolve(source, server(loaded, WORLD_ID));

        ResolvedModuleConfiguration resolved = result.module(GameKey.KNOCKBACK_SUMO).orElseThrow();
        assertTrue(resolved.admissionAllowed());
        assertTrue(resolved.gameConfiguration().orElseThrow() instanceof ResolvedGameConfiguration);
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.path().equals("modules.knockback-sumo.world")));
    }

    @Test
    void fullHeightRegionUsesWorldMinimumAndExclusiveMaximum() {
        WorldReference reference = new WorldReference("spawn", WORLD_ID);
        ModuleConfiguration sumo = new ModuleConfiguration(
                GameKey.KNOCKBACK_SUMO, true, Optional.of(reference),
                Map.of(
                        "side-a", new com.ciaac.minecraft.minigames.configuration.LocationSpec(reference, 0, 1, 0, 0, 0),
                        "side-b", new com.ciaac.minecraft.minigames.configuration.LocationSpec(reference, 2, 1, 0, 0, 0),
                        "exit", new com.ciaac.minecraft.minigames.configuration.LocationSpec(reference, 0, 1, 0, 0, 0)),
                Map.of(
                        "platform", new RegionSpec(reference, -5, 0, -5, 5, 10, 5),
                        "boundary", new RegionSpec(reference, -10, 0, -10, 10, 20, 10, true)),
                new ConfigValues(Map.of(
                        "enabled", true,
                        "minimum-players", 2,
                        "maximum-players", 2,
                        "best-of-rounds", 3,
                        "round-timeout-seconds", 90,
                        "fall-threshold-y", 0,
                        "knockback-item-material", "STICK",
                        "knockback-level", 2)), List.of());

        EnumMap<GameKey, ModuleConfiguration> modules = disabledModules();
        modules.put(GameKey.KNOCKBACK_SUMO, sumo);
        World loaded = world("spawn", WORLD_ID, -64, 320);
        ConfigurationResolutionResult result = new PaperConfigurationResolver().resolve(
                runtime(true, modules), server(loaded, WORLD_ID));

        var boundary = result.module(GameKey.KNOCKBACK_SUMO).orElseThrow().regions().get("boundary");
        assertEquals(-64, boundary.minY());
        assertEquals(319, boundary.maxY());
        assertTrue(boundary.contains(0, -64, 0));
        assertTrue(boundary.contains(0, 319, 0));
        assertFalse(boundary.contains(0, -65, 0));
        assertFalse(boundary.contains(0, 320, 0));
    }

    private static RuntimeConfiguration runtime(boolean admission, Map<GameKey, ModuleConfiguration> supplied) {
        EnumMap<GameKey, ModuleConfiguration> modules = disabledModules();
        modules.putAll(supplied);
        return new RuntimeConfiguration(2, admission, "pt-PT", Path.of("minigames.sqlite"), Path.of("recovery"),
                new AnnouncementConfiguration(Duration.ZERO, false, "minigames", Duration.ZERO, true, true), modules, List.of());
    }

    private static YamlConfiguration defaultConfiguration() {
        InputStream stream = PaperConfigurationResolverTest.class.getClassLoader()
                .getResourceAsStream("config.yml");
        if (stream == null) throw new IllegalStateException("config.yml test resource is missing");
        try (stream; InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not read config.yml test resource", failure);
        }
    }

    private static EnumMap<GameKey, ModuleConfiguration> disabledModules() {
        EnumMap<GameKey, ModuleConfiguration> modules = new EnumMap<>(GameKey.class);
        for (GameKey game : GameKey.values()) {
            modules.put(game, new ModuleConfiguration(game, false, Optional.empty(), Map.of(), Map.of(),
                    new ConfigValues(Map.of()), List.of()));
        }
        return modules;
    }

    private static World world(String name, UUID id) {
        return world(name, id, 0, 0);
    }

    private static World world(String name, UUID id, int minHeight, int maxHeight) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> {
            if (method.getName().equals("getName")) return name;
            if (method.getName().equals("getUID")) return id;
            if (method.getName().equals("getMinHeight")) return minHeight;
            if (method.getName().equals("getMaxHeight")) return maxHeight;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == float.class) return 0F;
            if (method.getReturnType() == double.class) return 0D;
            return null;
        });
    }

    private static Server server(World world, UUID expected) {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class}, (proxy, method, args) -> {
            if (method.getName().equals("getWorld") && args != null && args.length == 1) {
                return expected.equals(args[0]) ? world : null;
            }
            if (method.getName().equals("getWorlds")) return List.of(world);
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == float.class) return 0F;
            if (method.getReturnType() == double.class) return 0D;
            return null;
        });
    }
}
