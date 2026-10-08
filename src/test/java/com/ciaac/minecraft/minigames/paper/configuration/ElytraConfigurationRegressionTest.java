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

class ElytraConfigurationRegressionTest {
    private static final UUID WORLD_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRIMARY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void rejectsEveryRingEdgeThatExtendsOutsideTheCourseBoundary() {
        List<RegionSpec> outside = List.of(
                region(-1, 10, 10, 1, 20, 12),
                region(19, 10, 10, 21, 20, 12),
                region(10, -1, 10, 12, 1, 12),
                region(10, 99, 10, 12, 101, 12),
                region(10, 10, -1, 12, 20, 1),
                region(10, 10, 19, 12, 20, 21));
        for (RegionSpec invalidRing : outside) {
            ResolvedModuleConfiguration module = resolve(
                    new RegionSpec(reference(), 0, 0, 0, 20, 100, 20),
                    Map.of("ring-a", region(2, 10, 2, 4, 20, 4), "ring-b", invalidRing), 1);

            assertFalse(module.admissionAllowed());
            ResolutionDiagnostic diagnostic = module.diagnostics().stream()
                    .filter(value -> value.code().equals("RING_OUTSIDE_BOUNDARY"))
                    .findFirst().orElseThrow();
            assertEquals("modules.elytra-rings.ring-regions.ring-b", diagnostic.path());
            assertTrue(diagnostic.message().contains("região completa"));
        }
    }

    @Test
    void acceptsFullyContainedOrderedRingsAndComputesFiniteCenters() {
        ResolvedModuleConfiguration module = resolve(
                new RegionSpec(reference(), 29_999_990, 0, 0, 30_000_000, 100, 10),
                Map.of(
                        "ring-a", region(29_999_999, 10, 1, 30_000_000, 20, 3),
                        "ring-b", region(29_999_990, 10, 5, 29_999_991, 20, 7)), 1);

        assertTrue(module.admissionAllowed(), () -> module.diagnostics().toString());
        var rings = assertInstanceOf(ResolvedElytraRingsConfiguration.class,
                module.gameConfiguration().orElseThrow());
        assertTrue(Double.isFinite(rings.course().rings().getFirst().x()));
        assertEquals(29_999_999.5, rings.course().rings().getFirst().x());
    }

    @Test
    void retainsSingleRunnerConfigurationRequirement() {
        ResolvedModuleConfiguration module = resolve(
                new RegionSpec(reference(), -10, 0, -10, 10, 100, 10),
                Map.of("ring-a", region(-2, 10, -2, -1, 20, -1),
                        "ring-b", region(1, 10, 1, 2, 20, 2)), 2);

        assertFalse(module.admissionAllowed());
        assertTrue(module.diagnostics().stream().anyMatch(value ->
                value.code().equals("CONCURRENCY_UNSUPPORTED")
                        && value.path().equals("modules.elytra-rings.concurrent-runners")));
    }

    private static ResolvedModuleConfiguration resolve(RegionSpec boundary,
            Map<String, RegionSpec> rings, int concurrent) {
        World world = world("elytra", WORLD_ID, 0, 256);
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("world-template-marker", "test-template");
        values.put("ring-order", List.of("ring-a", "ring-b"));
        values.put("course-revision", "rev1");
        values.put("run-timeout-seconds", 600);
        values.put("preload-radius-chunks", 0);
        values.put("firework-rockets", 0);
        values.put("allow-rockets", true);
        values.put("concurrent-runners", concurrent);
        rings.forEach((id, region) -> {
            values.put("ring-regions." + id + ".min", List.of(region.minX(), region.minY(), region.minZ()));
            values.put("ring-regions." + id + ".max", List.of(region.maxX(), region.maxY(), region.maxZ()));
        });
        ModuleConfiguration elytra = new ModuleConfiguration(GameKey.ELYTRA_RINGS, true,
                Optional.of(reference()),
                Map.of("start", new LocationSpec(reference(), boundary.minX() + 0.5, 65, boundary.minZ() + 0.5, 0, 0),
                        "exit", new LocationSpec(reference(), boundary.minX() + 0.5, 65, boundary.minZ() + 0.5, 0, 0)),
                Map.of("course-boundary", boundary), new ConfigValues(values), List.of());
        RuntimeConfiguration source = runtime(elytra);
        World primary = world("world", PRIMARY_ID, 0, 256);
        return new PaperConfigurationResolver().resolve(source, server(world, primary))
                .module(GameKey.ELYTRA_RINGS).orElseThrow();
    }

    private static RegionSpec region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new RegionSpec(reference(), minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static WorldReference reference() { return new WorldReference("elytra", WORLD_ID); }

    private static RuntimeConfiguration runtime(ModuleConfiguration active) {
        EnumMap<GameKey, ModuleConfiguration> modules = new EnumMap<>(GameKey.class);
        for (GameKey game : GameKey.values()) {
            modules.put(game, game == active.game() ? active
                    : new ModuleConfiguration(game, false, Optional.empty(), Map.of(), Map.of(),
                            new ConfigValues(Map.of()), List.of()));
        }
        return new RuntimeConfiguration(2, true, "pt-PT", Path.of("minigames.sqlite"), Path.of("recovery"),
                new AnnouncementConfiguration(Duration.ZERO, false, "minigames", Duration.ZERO, true, true),
                modules, List.of());
    }

    static World world(String name, UUID id, int minimumHeight, int maximumHeight) {
        return (World) java.lang.reflect.Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[] {World.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUID" -> id;
                    case "getMinHeight" -> minimumHeight;
                    case "getMaxHeight" -> maximumHeight;
                    default -> defaultValue(method.getReturnType());
                });
    }

    static Server server(World target, World primary) {
        return (Server) java.lang.reflect.Proxy.newProxyInstance(Server.class.getClassLoader(),
                new Class<?>[] {Server.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getWorld" -> arguments != null && arguments.length == 1
                            && WORLD_ID.equals(arguments[0]) ? target : null;
                    case "getWorlds" -> List.of(primary, target);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
