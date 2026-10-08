package com.ciaac.minecraft.minigames.paper.archeryrange;

import static org.junit.jupiter.api.Assertions.*;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

class PaperArcheryTargetProbeTest {
    private static final UUID WORLD_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String TARGET = "board-a";
    private static final Map<String, Integer> SCORES = Map.of(
            "bullseye", 10, "inner", 7, "middle", 5, "outer", 2);

    @Test void readyRequiresAllBandsAndAllowsSameBandMultipleHitboxes() {
        Fixture fixture = new Fixture();
        for (String band : SCORES.keySet()) fixture.addTarget(band);
        fixture.addTarget("inner");
        fixture.addArmorStand(Set.of("ciaac-archery-target:another-board:bullseye"),
                fixture.world, 4, 2, 4, true, false, false);

        assertTrue(fixture.ready());
    }

    @Test void missingAnyConfiguredBandKeepsLaneClosed() {
        Fixture fixture = new Fixture();
        for (String band : Set.of("bullseye", "inner", "middle")) fixture.addTarget(band);

        assertFalse(fixture.ready());
    }

    @Test void matchingTargetOutsideLaneOrInAnotherWorldIsRejected() {
        Fixture outsideRegion = new Fixture();
        for (String band : SCORES.keySet()) {
            outsideRegion.addArmorStand(Set.of(tag(band)), outsideRegion.world,
                    band.equals("outer") ? 30 : 4, 2, 4, true, false, false);
        }
        assertFalse(outsideRegion.ready());

        Fixture wrongWorld = new Fixture();
        World otherWorld = world(UUID.randomUUID(), List.of());
        for (String band : SCORES.keySet()) {
            wrongWorld.addArmorStand(Set.of(tag(band)), band.equals("outer") ? otherWorld : wrongWorld.world,
                    4, 2, 4, true, false, false);
        }
        assertFalse(wrongWorld.ready());
    }

    @Test void matchingTargetRejectsDeadInvalidMarkerAndUnsupportedEntities() {
        Fixture dead = new Fixture();
        for (String band : SCORES.keySet()) dead.addTarget(band, band.equals("outer"), true, false);
        assertFalse(dead.ready());

        Fixture invalid = new Fixture();
        for (String band : SCORES.keySet()) invalid.addTarget(band, false, false, false);
        assertFalse(invalid.ready());

        Fixture marker = new Fixture();
        for (String band : SCORES.keySet()) marker.addTarget(band, true, false, band.equals("outer"));
        assertFalse(marker.ready());

        Fixture unsupported = new Fixture();
        for (String band : SCORES.keySet()) {
            if (band.equals("outer")) {
                unsupported.addEntity(Set.of(tag(band)), unsupported.world,
                        4, 2, 4, true, false, false);
            } else {
                unsupported.addTarget(band);
            }
        }
        assertFalse(unsupported.ready());
    }

    @Test void sameTargetWithAmbiguousOrUnknownBandTagFailsClosed() {
        Fixture ambiguous = new Fixture();
        for (String band : Set.of("bullseye", "middle", "outer")) ambiguous.addTarget(band);
        ambiguous.addArmorStand(
                Set.of(tag("inner"), tag("outer")), ambiguous.world, 4, 2, 4, true, false, false);
        assertFalse(ambiguous.ready());

        Fixture unknown = new Fixture();
        for (String band : Set.of("bullseye", "middle", "outer")) unknown.addTarget(band);
        unknown.addArmorStand(
                Set.of(tag("inner"), tag("gold")), unknown.world, 4, 2, 4, true, false, false);
        assertFalse(unknown.ready());
    }

    @Test void ignoresDifferentTargetAndRejectsInvalidRegionContract() {
        Fixture onlyOtherTarget = new Fixture();
        onlyOtherTarget.addArmorStand(Set.of("ciaac-archery-target:other-board:bullseye"),
                onlyOtherTarget.world, 4, 2, 4, true, false, false);
        assertFalse(onlyOtherTarget.ready());

        Fixture complete = new Fixture();
        for (String band : SCORES.keySet()) complete.addTarget(band);
        ProtectedRegion mutable = new ProtectedRegion(complete.region.id(), GameKey.ARCHERY_RANGE,
                complete.region.bounds(), ProtectedRegionRole.PARTICIPANT_ONLY, false);
        assertFalse(PaperArcheryTargetProbe.ready(complete.lane, mutable, SCORES));
        assertFalse(PaperArcheryTargetProbe.ready(complete.lane, complete.region, Map.of("inner", 7)));
    }

    private static String tag(String band) {
        return "ciaac-archery-target:" + TARGET + ":" + band;
    }

    private static World world(UUID id, List<Entity> entities) {
        return proxy(World.class, method -> switch (method.getName()) {
            case "getUID" -> id;
            case "getEntities" -> entities;
            case "isChunkLoaded", "getChunkAt", "loadChunk" -> throw new AssertionError(
                    "readiness probe must not load or inspect chunks");
            default -> defaultValue(method.getReturnType());
        });
    }

    private static ArmorStand armorStand(World world, double x, double y, double z, Set<String> tags,
            boolean valid, boolean dead, boolean marker) {
        return proxy(ArmorStand.class, method -> switch (method.getName()) {
            case "getWorld" -> world;
            case "getLocation" -> new Location(world, x, y, z);
            case "getScoreboardTags" -> tags;
            case "isValid" -> valid;
            case "isDead" -> dead;
            case "isMarker" -> marker;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        Object value = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + " test proxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> null;
                        };
                    }
                    return invocation.invoke(method);
                });
        return type.cast(value);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0;
        if (type == char.class) return '\0';
        throw new AssertionError("unknown primitive: " + type);
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method) throws Throwable;
    }

    private static final class Fixture {
        private final List<Entity> entities = new ArrayList<>();
        private final World world = world(WORLD_ID, entities);
        private final ArcheryPaperSettings.LaneSettings lane = new ArcheryPaperSettings.LaneSettings(
                0, "archery-range.lane-0", new Location(world, 2, 2, 2), TARGET);
        private final ProtectedRegion region = new ProtectedRegion(lane.regionId(), GameKey.ARCHERY_RANGE,
                new CuboidRegion(WORLD_ID, 0, 0, 0, 16, 8, 16),
                ProtectedRegionRole.PARTICIPANT_ONLY, true);
        private boolean ready() {
            return PaperArcheryTargetProbe.ready(lane, region, SCORES);
        }

        private void addTarget(String band) {
            addTarget(band, true, false, false);
        }

        private void addTarget(String band, boolean valid, boolean dead, boolean marker) {
            addArmorStand(Set.of(tag(band)), world, 4, 2, 4, valid, dead, marker);
        }

        private void addArmorStand(Set<String> tags, World targetWorld,
                double x, double y, double z, boolean valid, boolean dead, boolean marker) {
            entities.add(armorStand(targetWorld, x, y, z, tags, valid, dead, marker));
        }

        private void addEntity(Set<String> tags, World targetWorld,
                double x, double y, double z, boolean valid, boolean dead, boolean marker) {
            Entity entity = proxy(Entity.class, method -> switch (method.getName()) {
                case "getWorld" -> targetWorld;
                case "getLocation" -> new Location(targetWorld, x, y, z);
                case "getScoreboardTags" -> tags;
                case "isValid" -> valid;
                case "isDead" -> dead;
                default -> defaultValue(method.getReturnType());
            });
            entities.add(entity);
        }
    }
}
