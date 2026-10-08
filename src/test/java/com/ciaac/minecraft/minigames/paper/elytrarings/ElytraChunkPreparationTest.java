package com.ciaac.minecraft.minigames.paper.elytrarings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.elytrarings.ElytraCourseRevision;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsConfig;
import com.ciaac.minecraft.minigames.elytrarings.RingCheckpoint;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.time.Duration;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class ElytraChunkPreparationTest {
    private static final java.util.UUID WORLD_ID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void footprintCoversIntermediateRouteAndConfiguredRadius() {
        Set<ElytraChunkPreparation.ChunkCoordinate> chunks = ElytraChunkPreparation.requiredChunks(
                new Location(null, 0, 100, 0),
                List.of(new RingCheckpoint(1, 48, 100, 0),
                        new RingCheckpoint(2, 48, 100, 32)),
                1);

        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(0, 0)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(1, 0)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(2, 0)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(3, 0)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(3, 2)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(2, 1)));
    }

    @Test
    void oversizedRouteFailsBeforeAnyPaperOperation() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ElytraChunkPreparation.requiredChunks(
                        new Location(null, 0, 100, 0),
                        List.of(new RingCheckpoint(1,
                                (double) (16L * (ElytraChunkPreparation.MAX_REQUIRED_CHUNKS + 1L)),
                                100, 0)),
                        0));

        assertEquals("PRELOAD_FOOTPRINT_TOO_LARGE", failure.getMessage());
    }

    @Test
    void footprintIncludesEveryChunkIntersectingConfiguredRingRegions() {
        var chunks = ElytraChunkPreparation.requiredChunks(
                new Location(null, 0, 100, 0),
                List.of(new RingCheckpoint(1, 16, 100, 0)), 1,
                List.of(new CuboidRegion(WORLD_ID, 160, 20, -33, 200, 240, -17)));

        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(10, -3)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(12, -2)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(11, -3)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(0, 0)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(1, 0)));
    }

    @Test
    void ringRegionChunkBoundsUseFloorDivisionAtNegativeCoordinates() {
        var chunks = ElytraChunkPreparation.requiredChunks(
                new Location(null, 0, 100, 0), List.of(), 0,
                List.of(new CuboidRegion(WORLD_ID, -17, 0, -33, -16, 10, -16)));

        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(-2, -3)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(-2, -2)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(-1, -3)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(-1, -2)));
        assertFalse(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(-3, -2)));
    }

    @Test
    void excessiveRingFootprintFailsBeforeTicketOrLoadMutation() {
        WorldState worldState = new WorldState();
        World world = proxy(World.class, worldState::invoke);
        Plugin plugin = plugin();
        var settings = settings(world, 0, 0, List.of(new CuboidRegion(
                WORLD_ID, 0, 0, 0, 16 * (ElytraChunkPreparation.MAX_REQUIRED_CHUNKS + 1), 10, 0)));

        var preparation = new ElytraChunkPreparation(settings, plugin);
        preparation.tick();

        assertEquals(ElytraChunkPreparation.Phase.FAILED, preparation.status().phase());
        assertEquals("PRELOAD_FOOTPRINT_TOO_LARGE", preparation.status().code());
        assertTrue(worldState.addAttempts.isEmpty());
        assertTrue(worldState.asyncLoadCalls.isEmpty());
        assertTrue(worldState.isLoadedCalls.isEmpty());
    }

    @Test
    void ticketFailureReleasesOnlyPreviouslyAcquiredTicketsBeforeAnyLoads() {
        WorldState worldState = new WorldState();
        worldState.failTicketAt = new ElytraChunkPreparation.ChunkCoordinate(1, 0);
        World world = proxy(World.class, worldState::invoke);
        var settings = settings(world, 16, 0, List.of());
        var preparation = new ElytraChunkPreparation(settings, plugin());

        preparation.tick();

        assertEquals(ElytraChunkPreparation.Phase.FAILED, preparation.status().phase());
        assertEquals("CHUNK_TICKET_FAILED", preparation.status().code());
        assertEquals(List.of(new ElytraChunkPreparation.ChunkCoordinate(0, 0),
                new ElytraChunkPreparation.ChunkCoordinate(1, 0)), worldState.addAttempts);
        assertEquals(List.of(new ElytraChunkPreparation.ChunkCoordinate(0, 0)), worldState.removals);
        assertTrue(worldState.asyncLoadCalls.isEmpty());
        assertTrue(worldState.isLoadedCalls.isEmpty());
        assertTrue(worldState.tickets.isEmpty());
    }

    @Test
    void releaseRemovesItsTicketsAndLeavesOtherChunksAlone() {
        WorldState worldState = new WorldState();
        ElytraChunkPreparation.ChunkCoordinate unrelated = new ElytraChunkPreparation.ChunkCoordinate(99, -99);
        worldState.tickets.add(unrelated);
        World world = proxy(World.class, worldState::invoke);
        var preparation = new ElytraChunkPreparation(settings(world, 0, 0, List.of()), plugin());

        preparation.tick();
        assertTrue(preparation.admissionReady());
        preparation.release();

        assertEquals(ElytraChunkPreparation.Phase.RELEASED, preparation.status().phase());
        assertEquals(List.of(new ElytraChunkPreparation.ChunkCoordinate(0, 0)), worldState.removals);
        assertEquals(Set.of(unrelated), worldState.tickets);
    }

    private static ElytraRingsPaperSettings settings(World world, double ringX, double ringZ,
            List<CuboidRegion> ringRegions) {
        var course = new ElytraCourseRevision("v1", WORLD_ID.toString(),
                List.of(new RingCheckpoint(1, ringX, 100, ringZ), new RingCheckpoint(2, ringX, 102, ringZ)));
        var config = ElytraRingsConfig.dedicatedWorld(Duration.ofMinutes(2), course);
        Location start = new Location(world, 0, 100, 0);
        return new ElytraRingsPaperSettings(true, config, world, "elytra-course", start,
                3.0, 0, 0, ringRegions.size() == 1 ? List.of(ringRegions.getFirst(), ringRegions.getFirst()) : ringRegions);
    }

    private static Plugin plugin() {
        Server server = proxy(Server.class, (instance, method, arguments) -> switch (method.getName()) {
            case "isPrimaryThread" -> true;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(Plugin.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getServer" -> server;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }

    private static final class WorldState {
        private final List<ElytraChunkPreparation.ChunkCoordinate> addAttempts = new ArrayList<>();
        private final List<ElytraChunkPreparation.ChunkCoordinate> removals = new ArrayList<>();
        private final List<ElytraChunkPreparation.ChunkCoordinate> asyncLoadCalls = new ArrayList<>();
        private final List<ElytraChunkPreparation.ChunkCoordinate> isLoadedCalls = new ArrayList<>();
        private final Set<ElytraChunkPreparation.ChunkCoordinate> tickets = new HashSet<>();
        private ElytraChunkPreparation.ChunkCoordinate failTicketAt;

        private Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "getUID" -> WORLD_ID;
                case "getName" -> "elytra-test";
                case "addPluginChunkTicket" -> {
                    var coordinate = new ElytraChunkPreparation.ChunkCoordinate((int) arguments[0], (int) arguments[1]);
                    addAttempts.add(coordinate);
                    if (coordinate.equals(failTicketAt)) yield false;
                    tickets.add(coordinate);
                    yield true;
                }
                case "removePluginChunkTicket" -> {
                    var coordinate = new ElytraChunkPreparation.ChunkCoordinate((int) arguments[0], (int) arguments[1]);
                    removals.add(coordinate);
                    tickets.remove(coordinate);
                    yield true;
                }
                case "isChunkLoaded" -> {
                    isLoadedCalls.add(new ElytraChunkPreparation.ChunkCoordinate((int) arguments[0], (int) arguments[1]));
                    yield true;
                }
                case "getChunkAtAsync" -> {
                    asyncLoadCalls.add(new ElytraChunkPreparation.ChunkCoordinate((int) arguments[0], (int) arguments[1]));
                    yield java.util.concurrent.CompletableFuture.<Chunk>failedFuture(new IllegalStateException("unexpected load"));
                }
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    @Test
    void disabledPreparationNeverAdmits() {
        var course = new ElytraCourseRevision(
                "v1", "00000000-0000-0000-0000-000000000001",
                List.of(new RingCheckpoint(1, 0, 100, 0),
                        new RingCheckpoint(2, 16, 100, 0)));
        var settings = ElytraRingsPaperSettings.disabled(
                ElytraRingsConfig.dedicatedWorld(Duration.ofMinutes(2), course),
                "elytra-course");
        var preparation = ElytraChunkPreparation.unavailable(settings, "CHUNK_PREPARER_UNAVAILABLE");

        assertEquals(ElytraChunkPreparation.Phase.DISABLED, preparation.status().phase());
        assertFalse(preparation.admissionReady());
    }
}
