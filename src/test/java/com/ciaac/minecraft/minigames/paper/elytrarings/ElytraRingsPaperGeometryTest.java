package com.ciaac.minecraft.minigames.paper.elytrarings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.elytrarings.ElytraCourseRevision;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsConfig;
import com.ciaac.minecraft.minigames.elytrarings.RingCheckpoint;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.lang.reflect.Proxy;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class ElytraRingsPaperGeometryTest {
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final double EPSILON = 1.0e-12;

    @Test
    void exactCuboidReturnsEarliestContinuousEntryFraction() {
        var settings = settings(false);

        OptionalDouble crossing = settings.ringCrossing(1, at(-1, .5, .5), at(2, .5, .5));
        assertFraction(1.0 / 3.0, crossing);
        assertFraction(1.0 / 3.0, settings.ringCrossing(1, at(2, .5, .5), at(-1, .5, .5)));
        assertFraction(0.0, settings.ringCrossing(1, at(.25, .25, .25), at(2, .25, .25)));
        assertFraction(0.0, settings.ringCrossing(1, at(.25, .25, .25), at(.25, .25, .25)));
        assertFalse(settings.ringCrossing(1, at(2, 2, 2), at(2, 2, 2)).isPresent());
    }

    @Test
    void halfOpenMaximumFaceRejectsGrazesWhileMinimumFaceIsIncluded() {
        var settings = settings(false);

        assertFalse(settings.ringCrossing(1, at(1, -1, .5), at(1, 2, .5)).isPresent());
        assertFalse(settings.ringCrossing(1, at(2, .5, .5), at(1, .5, .5)).isPresent());
        assertFraction(1.0, settings.ringCrossing(1, at(-1, .5, .5), at(0, .5, .5)));
        // Starting on an open maximum face and moving inward returns its entry infimum.
        assertFraction(0.0, settings.ringCrossing(1, at(1, .5, .5), at(.5, .5, .5)));
    }

    @Test
    void reportsIndependentFractionsForOrderedRingsAlongOneSegment() {
        var settings = settings(false);
        OptionalDouble first = settings.ringCrossing(1, at(-1, .5, .5), at(9, .5, .5));
        OptionalDouble second = settings.ringCrossing(2, at(-1, .5, .5), at(9, .5, .5));

        assertFraction(.1, first);
        assertFraction(.5, second);
        assertTrue(first.getAsDouble() < second.getAsDouble());
    }

    @Test
    void rejectsInvalidOrderWorldAndNonFiniteCoordinates() {
        var settings = settings(false);
        assertFalse(settings.ringCrossing(0, at(-1, .5, .5), at(2, .5, .5)).isPresent());
        assertFalse(settings.ringCrossing(3, at(-1, .5, .5), at(2, .5, .5)).isPresent());
        assertFalse(settings.ringCrossing(1, at(world(UUID.randomUUID(), "geometry"), -1, .5, .5),
                at(2, .5, .5)).isPresent());
        assertFalse(settings.ringCrossing(1, at(world(WORLD_ID, "renamed"), -1, .5, .5),
                at(2, .5, .5)).isPresent());
        assertFalse(settings.ringCrossing(1, at(Double.NaN, .5, .5), at(2, .5, .5)).isPresent());
        assertFalse(settings.ringCrossing(1, at(-1, .5, .5), at(Double.POSITIVE_INFINITY, .5, .5)).isPresent());
    }

    @Test
    void legacyFixtureFallbackUsesSegmentSphereIntersection() {
        var settings = settings(true);

        assertFraction(.375, settings.ringCrossing(1, at(-2, .5, .5), at(2, .5, .5)));
        assertFraction(.625, settings.ringCrossing(1, at(-2, 1.5, .5), at(2, 1.5, .5)));
        assertFraction(0.0, settings.ringCrossing(1, at(.5, .5, .5), at(.5, .5, .5)));
        assertFalse(settings.ringCrossing(1, at(.5, 2, .5), at(.5, 2, .5)).isPresent());
    }

    private static ElytraRingsPaperSettings settings(boolean legacy) {
        World world = world(WORLD_ID, "geometry");
        var course = new ElytraCourseRevision("v1", WORLD_ID.toString(), List.of(
                new RingCheckpoint(1, .5, .5, .5), new RingCheckpoint(2, 4.5, .5, .5)));
        var config = ElytraRingsConfig.dedicatedWorld(Duration.ofMinutes(2), course);
        List<CuboidRegion> regions = List.of(
                new CuboidRegion(WORLD_ID, 0, 0, 0, 0, 0, 0),
                new CuboidRegion(WORLD_ID, 4, 0, 0, 4, 0, 0));
        return new ElytraRingsPaperSettings(true, config, world, "elytra-course",
                new Location(world, .5, 1, .5), 1.0, 0, 0, legacy ? List.of() : regions);
    }

    private static Location at(double x, double y, double z) {
        return at(world(WORLD_ID, "geometry"), x, y, z);
    }

    private static Location at(World world, double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    private static World world(UUID id, String name) {
        return proxy(World.class, method -> switch (method.getName()) {
            case "getUID" -> id;
            case "getName" -> name;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static void assertFraction(double expected, OptionalDouble actual) {
        assertTrue(actual.isPresent());
        assertEquals(expected, actual.getAsDouble(), EPSILON);
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        @SuppressWarnings("unchecked")
        T proxy = (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (target, method, args) -> invocation.invoke(method));
        return proxy;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    @FunctionalInterface private interface Invocation {
        Object invoke(java.lang.reflect.Method method) throws Throwable;
    }
}
