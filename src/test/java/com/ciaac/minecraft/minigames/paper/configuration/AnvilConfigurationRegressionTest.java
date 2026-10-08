package com.ciaac.minecraft.minigames.paper.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

class AnvilConfigurationRegressionTest {
    private static final UUID WORLD_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void acceptsExistingMultiHeightFloorAndStartAtFloorMaximum() {
        ResolvedModuleConfiguration module = resolve(
                new RegionSpec(reference(), -2, 70, -2, 3, 100, 3), 0, 256);

        assertTrue(module.admissionAllowed(), () -> module.diagnostics().toString());
    }

    @Test
    void hazardEnvelopeMustFitBoundaryFloorFootprintAndWorldHeight() {
        ResolvedModuleConfiguration xzTooSmall = resolve(
                new RegionSpec(reference(), -2, 70, -2, 0, 100, 3), 0, 256);
        assertEnvelopeRejected(xzTooSmall);

        ResolvedModuleConfiguration boundaryTooLow = resolve(
                new RegionSpec(reference(), -2, 70, -2, 3, 86, 3), 0, 256);
        assertEnvelopeRejected(boundaryTooLow);

        ResolvedModuleConfiguration worldTooShort = resolve(
                new RegionSpec(reference(), -2, 70, -2, 3, 100, 3), 0, 87);
        assertEnvelopeRejected(worldTooShort);
    }

    @Test
    void totalPlannedMarkersMustFitOwnershipLimitUsingLongArithmetic() {
        ResolvedModuleConfiguration atLimit = resolve(
                new RegionSpec(reference(), -2, 70, -2, 3, 100, 3), 0, 256, 4_096, 1, 0);
        assertTrue(atLimit.admissionAllowed(), () -> atLimit.diagnostics().toString());

        ResolvedModuleConfiguration overLimit = resolve(
                new RegionSpec(reference(), -2, 70, -2, 3, 100, 3), 0, 256, 4_097, 1, 0);
        assertFalse(overLimit.admissionAllowed(), () -> overLimit.diagnostics().toString());
        assertTrue(overLimit.diagnostics().stream().anyMatch(value ->
                value.code().equals("ANVIL_MARKER_LIMIT_EXCEEDED")
                        && value.path().equals("modules.anvil-dodge.wave-count")),
                () -> overLimit.diagnostics().toString());

        ResolvedModuleConfiguration largePlan = resolve(
                new RegionSpec(reference(), -2, 70, -2, 3, 100, 3), 0, 256, 10_000, 4_096, 4_096);
        assertFalse(largePlan.admissionAllowed(), () -> largePlan.diagnostics().toString());
        assertTrue(largePlan.diagnostics().stream().anyMatch(value ->
                value.code().equals("ANVIL_MARKER_LIMIT_EXCEEDED")),
                () -> largePlan.diagnostics().toString());
    }

    private static void assertEnvelopeRejected(ResolvedModuleConfiguration module) {
        assertFalse(module.admissionAllowed(), () -> module.diagnostics().toString());
        assertTrue(module.diagnostics().stream().anyMatch(value ->
                value.code().equals("ANVIL_ENVELOPE_OUTSIDE_BOUNDARY")
                        && value.path().equals("modules.anvil-dodge.regions.boundary")),
                () -> module.diagnostics().toString());
    }

    private static ResolvedModuleConfiguration resolve(RegionSpec boundary, int minimumHeight, int maximumHeight) {
        return resolve(boundary, minimumHeight, maximumHeight, 3, 2, 1);
    }

    private static ResolvedModuleConfiguration resolve(RegionSpec boundary, int minimumHeight, int maximumHeight,
            int waves, int hazards, int increment) {
        World world = world(minimumHeight, maximumHeight);
        WorldReference reference = reference();
        Map<String, Object> values = Map.of(
                "minimum-players", 1,
                "maximum-players", 4,
                "wave-count", waves,
                "warning-ticks", 20,
                "wave-interval-ticks", 40,
                "hazards-per-wave-start", hazards,
                "hazards-per-wave-increment", increment);
        ModuleConfiguration anvil = new ModuleConfiguration(GameKey.ANVIL_DODGE, true, Optional.of(reference),
                Map.of("start", new LocationSpec(reference, 0.5, 80, 0.5, 0, 0),
                        "exit", new LocationSpec(reference, -1.5, 81, -1.5, 0, 0)),
                Map.of("floor", new RegionSpec(reference, 0, 79, 0, 1, 80, 1), "boundary", boundary),
                new ConfigValues(values), List.of());
        EnumMap<GameKey, ModuleConfiguration> modules = new EnumMap<>(GameKey.class);
        for (GameKey game : GameKey.values()) {
            modules.put(game, game == GameKey.ANVIL_DODGE ? anvil
                    : new ModuleConfiguration(game, false, Optional.empty(), Map.of(), Map.of(),
                            new ConfigValues(Map.of()), List.of()));
        }
        RuntimeConfiguration source = new RuntimeConfiguration(2, true, "pt-PT", Path.of("minigames.sqlite"),
                Path.of("recovery"),
                new AnnouncementConfiguration(Duration.ZERO, false, "minigames", Duration.ZERO, true, true),
                modules, List.of());
        return new PaperConfigurationResolver().resolve(source, server(world))
                .module(GameKey.ANVIL_DODGE).orElseThrow();
    }

    private static WorldReference reference() { return new WorldReference("world", WORLD_ID); }

    private static World world(int minimumHeight, int maximumHeight) {
        return (World) java.lang.reflect.Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[] {World.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "world";
                    case "getUID" -> WORLD_ID;
                    case "getMinHeight" -> minimumHeight;
                    case "getMaxHeight" -> maximumHeight;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Server server(World world) {
        return (Server) java.lang.reflect.Proxy.newProxyInstance(Server.class.getClassLoader(),
                new Class<?>[] {Server.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getWorld" -> arguments != null && arguments.length == 1
                            && WORLD_ID.equals(arguments[0]) ? world : null;
                    case "getWorlds" -> List.of(world);
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
