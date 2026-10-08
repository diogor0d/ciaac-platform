package com.ciaac.minecraft.minigames.paper.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.configuration.AnnouncementConfiguration;
import com.ciaac.minecraft.minigames.configuration.ConfigValues;
import com.ciaac.minecraft.minigames.configuration.LocationSpec;
import com.ciaac.minecraft.minigames.configuration.ModuleConfiguration;
import com.ciaac.minecraft.minigames.configuration.RegionSpec;
import com.ciaac.minecraft.minigames.configuration.RuntimeConfiguration;
import com.ciaac.minecraft.minigames.configuration.WorldReference;
import com.ciaac.minecraft.minigames.core.GameKey;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class ArcheryConfigurationRegressionTest {
    private static final UUID WORLD_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void bowIsAcceptedAndDomainLimitUsesHighestConfiguredBand() {
        ResolvedFixture fixture = resolve("BOW", Map.of(
                "bullseye", 4, "inner", 9, "middle", 5, "outer", 2));

        ResolvedModuleConfiguration module = fixture.module();
        assertTrue(module.admissionAllowed());
        ResolvedArcheryConfiguration archery = assertInstanceOf(
                ResolvedArcheryConfiguration.class, module.gameConfiguration().orElseThrow());
        assertEquals(9, archery.lanes().get(0).domain().maxScorePerShot());
        assertEquals(WORLD_ID, fixture.world().getUID());
    }

    @Test
    void allZeroScoresResolveWithSafePositiveDomainLimit() {
        ResolvedFixture fixture = resolve("BOW", Map.of(
                "bullseye", 0, "inner", 0, "middle", 0, "outer", 0));

        ResolvedModuleConfiguration module = fixture.module();
        assertTrue(module.admissionAllowed());
        ResolvedArcheryConfiguration archery = assertInstanceOf(
                ResolvedArcheryConfiguration.class, module.gameConfiguration().orElseThrow());
        assertEquals(1, archery.lanes().get(0).domain().maxScorePerShot());
        assertEquals(WORLD_ID, fixture.world().getUID());
    }

    @Test
    void nonBowMaterialsFailClosedBeforeAdmission() {
        for (String material : List.of("STICK", "CROSSBOW", "NOT_A_MATERIAL")) {
            ResolvedFixture fixture = resolve(material, Map.of(
                    "bullseye", 10, "inner", 7, "middle", 5, "outer", 2));

            ResolvedModuleConfiguration module = fixture.module();
            assertFalse(module.admissionAllowed(), material);
            assertTrue(module.diagnostics().stream().anyMatch(diagnostic ->
                    diagnostic.code().equals("MATERIAL_UNSUPPORTED")
                            && diagnostic.path().equals("modules.archery-range.bow-material")), material);
            assertEquals(WORLD_ID, fixture.world().getUID());
        }
    }

    private static ResolvedFixture resolve(String material, Map<String, Integer> scores) {
        WorldReference reference = new WorldReference("spawn", WORLD_ID);
        EnumMap<GameKey, ModuleConfiguration> modules = disabledModules();
        modules.put(GameKey.ARCHERY_RANGE, archeryModule(reference, material, scores));
        RuntimeConfiguration source = new RuntimeConfiguration(2, true, "pt-PT", Path.of("minigames.sqlite"),
                Path.of("recovery"),
                new AnnouncementConfiguration(Duration.ZERO, false, "minigames", Duration.ZERO, true, true),
                modules, List.of());
        // Keep the synthetic World strongly reachable throughout resolution and its assertions.
        World world = world("spawn", WORLD_ID);
        ResolvedModuleConfiguration module = new PaperConfigurationResolver().resolve(source, server(world))
                .module(GameKey.ARCHERY_RANGE).orElseThrow();
        return new ResolvedFixture(world, module);
    }

    private record ResolvedFixture(World world, ResolvedModuleConfiguration module) { }

    private static ModuleConfiguration archeryModule(
            WorldReference reference, String material, Map<String, Integer> scores) {
        Map<String, Object> values = Map.ofEntries(
                Map.entry("shots-per-attempt", 5),
                Map.entry("attempt-timeout-seconds", 120),
                Map.entry("target-scores.bullseye", scores.get("bullseye")),
                Map.entry("target-scores.inner", scores.get("inner")),
                Map.entry("target-scores.middle", scores.get("middle")),
                Map.entry("target-scores.outer", scores.get("outer")),
                Map.entry("bow-material", material),
                Map.entry("lanes.lane-0.region-id", "lane-0"),
                Map.entry("lanes.lane-0.target-id", "score-target"),
                Map.entry("lanes.lane-0.spawn.x", 2),
                Map.entry("lanes.lane-0.spawn.y", 65),
                Map.entry("lanes.lane-0.spawn.z", 2));
        return new ModuleConfiguration(GameKey.ARCHERY_RANGE, true, Optional.of(reference),
                Map.of("exit", new LocationSpec(reference, 2, 65, 2, 0, 0)),
                Map.of(
                        "range-boundary", new RegionSpec(reference, 0, 60, 0, 16, 80, 16),
                        "lane-0", new RegionSpec(reference, 0, 60, 0, 16, 80, 16)),
                new ConfigValues(values), List.of());
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
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getName")) return name;
                    if (method.getName().equals("getUID")) return id;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == float.class) return 0F;
                    if (method.getReturnType() == double.class) return 0D;
                    return null;
                });
    }

    private static Server server(World world) {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getWorld") && arguments != null && arguments.length == 1) {
                        return WORLD_ID.equals(arguments[0]) ? world : null;
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
