package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.colorfloor.FloorColor;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.isolation.WorldRecoveryPendingException;
import com.ciaac.minecraft.minigames.persistence.AuditEvent;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.persistence.ColorFloorWorldLedger;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pt.ciaac.minigames.paper.template.TemplateArtifact;

/** Exercises the provider against durable stores and guarded Bukkit proxies. */
class ColorFloorWorldStateTest {
    private static final UUID WORLD_ID = uuid(1);
    private static final UUID PLAYER_ID = uuid(2);
    private static final UUID CONNECTION_ID = uuid(3);
    private static final Instant CAPTURED_AT = Instant.parse("2026-10-07T12:00:00Z");

    @TempDir Path temporary;
    private Field bukkitServerField;
    private Object previousBukkitServer;

    @BeforeEach
    void captureBukkitServer() throws ReflectiveOperationException {
        bukkitServerField = Bukkit.class.getDeclaredField("server");
        bukkitServerField.setAccessible(true);
        previousBukkitServer = bukkitServerField.get(null);
    }

    @AfterEach
    void restoreBukkitServer() throws IllegalAccessException {
        bukkitServerField.set(null, previousBukkitServer);
    }

    @Test
    void durableCaptureEnterAirCleanupAndRestoreRetryAfterBoundedBatch() {
        int cells = 257;
        var fixture = new Fixture(manifest(cells, WORLD_ID, "color-floor"));
        var capture = operation(PlayerStateOperation.Kind.CAPTURE, 10, 20, 30, PLAYER_ID);
        byte[] payload;
        Path ledgerPath = temporary.resolve("ledger");
        Path journalPath = temporary.resolve("journal");

        try (var ledger = new ColorFloorWorldLedger(ledgerPath);
             var journal = new ExternalOperationJournal(journalPath)) {
            payload = fixture.state(ledger, journal, fixture.regions).capture(capture);
        }

        PlayerStateOperation enter = phase(capture, PlayerStateOperation.Kind.ENTER, 11);
        PlayerStateOperation purge = phase(capture, PlayerStateOperation.Kind.PURGE, 12);
        PlayerStateOperation restore = phase(capture, PlayerStateOperation.Kind.RESTORE, 13);
        try (var ledger = new ColorFloorWorldLedger(ledgerPath);
             var journal = new ExternalOperationJournal(journalPath)) {
            var state = fixture.state(ledger, journal, fixture.regions);
            assertArrayEquals(payload, state.capture(capture));
            state.enter(enter);
            fixture.world.setAll("minecraft:air");

            assertThrows(WorldRecoveryPendingException.class, () -> state.purge(purge));
            assertEquals(ColorFloorWorldLedger.Status.PURGING, ledger.requireLease(purge).status());
            assertEquals(256, fixture.world.count("minecraft:red_concrete"));
            assertEquals(1, fixture.world.count("minecraft:air"));
        }

        // A restart resumes from durable lease state and compares each remaining block before writing.
        try (var ledger = new ColorFloorWorldLedger(ledgerPath);
             var journal = new ExternalOperationJournal(journalPath)) {
            var state = fixture.state(ledger, journal, fixture.regions);
            state.purge(purge);
            assertEquals(ColorFloorWorldLedger.Status.PURGED, ledger.requireLease(purge).status());
            assertEquals(cells, fixture.world.count("minecraft:red_concrete"));
            state.restore(restore, 1, payload);
            assertEquals(ColorFloorWorldLedger.Status.RESTORED, ledger.requireLease(restore).status());
        }
    }

    @Test
    void rejectsFrozenWorldTemplateAndProtectedGeometryDrift() {
        var original = manifest(3, WORLD_ID, "color-floor");
        var fixture = new Fixture(original);
        var capture = operation(PlayerStateOperation.Kind.CAPTURE, 20, 21, 22, PLAYER_ID);
        try (var ledger = new ColorFloorWorldLedger(temporary.resolve("drift-ledger"));
             var journal = new ExternalOperationJournal(temporary.resolve("drift-journal"))) {
            var state = fixture.state(ledger, journal, fixture.regions);
            byte[] payload = state.capture(capture);
            PlayerStateOperation enter = phase(capture, PlayerStateOperation.Kind.ENTER, 23);

            fixture.currentManifest.set(manifest(3, WORLD_ID, "color-floor", "revision-2"));
            assertThrows(IllegalStateException.class, () -> state.validate(enter, 1, payload));

            fixture.currentManifest.set(manifest(3, uuid(900), "color-floor"));
            assertThrows(IllegalStateException.class, () -> state.validate(enter, 1, payload));

            fixture.currentManifest.set(original);
            var movedBoundary = boundary(original, original.boundary().bounds().maxX() + 1);
            var changedRegions = new ProtectedRegionRegistry();
            changedRegions.register(movedBoundary);
            var changedGeometry = fixture.state(ledger, journal, changedRegions);
            assertThrows(IllegalStateException.class, () -> changedGeometry.validate(enter, 1, payload));
        }
    }

    @Test
    void thirdPartyBlockIsNeverOverwrittenDuringCleanup() {
        var fixture = new Fixture(manifest(4, WORLD_ID, "color-floor"));
        var capture = operation(PlayerStateOperation.Kind.CAPTURE, 30, 31, 32, PLAYER_ID);
        try (var ledger = new ColorFloorWorldLedger(temporary.resolve("third-party-ledger"));
             var journal = new ExternalOperationJournal(temporary.resolve("third-party-journal"))) {
            var state = fixture.state(ledger, journal, fixture.regions);
            byte[] payload = state.capture(capture);
            state.enter(phase(capture, PlayerStateOperation.Kind.ENTER, 33));
            fixture.world.setAll("minecraft:air");
            fixture.world.set(0, 64, 0, "minecraft:stone");
            int writesBefore = fixture.world.writes.get();

            assertThrows(IllegalStateException.class,
                    () -> state.purge(phase(capture, PlayerStateOperation.Kind.PURGE, 34)));
            assertEquals("minecraft:stone", fixture.world.get(0, 64, 0).getAsString());
            assertEquals(0, fixture.world.writes.get() - writesBefore);
            assertEquals(ColorFloorWorldLedger.Status.ARMED,
                    ledger.requireLease(phase(capture, PlayerStateOperation.Kind.PURGE, 35)).status());
            assertTrue(payload.length > 0);
        }
    }

    @Test
    void unloadedOwnedChunkIsRejectedWithoutLoadingOrWriting() {
        var fixture = new Fixture(manifest(20, WORLD_ID, "color-floor"));
        var capture = operation(PlayerStateOperation.Kind.CAPTURE, 40, 41, 42, PLAYER_ID);
        try (var ledger = new ColorFloorWorldLedger(temporary.resolve("unloaded-ledger"));
             var journal = new ExternalOperationJournal(temporary.resolve("unloaded-journal"))) {
            var state = fixture.state(ledger, journal, fixture.regions);
            state.capture(capture);
            state.enter(phase(capture, PlayerStateOperation.Kind.ENTER, 43));
            fixture.world.setAll("minecraft:air");
            int readsBefore = fixture.world.blockReads.get();
            int writesBefore = fixture.world.writes.get();
            fixture.world.setChunkLoaded(0, 0, false);

            assertThrows(IllegalStateException.class,
                    () -> state.purge(phase(capture, PlayerStateOperation.Kind.PURGE, 44)));
            assertEquals(readsBefore, fixture.world.blockReads.get());
            assertEquals(writesBefore, fixture.world.writes.get());
            assertEquals(0, fixture.world.chunkLoads.get());
        }
    }

    @Test
    void twoPlayersShareOneFrozenFacilityAndBothLeasesCleanSafely() {
        var fixture = new Fixture(manifest(8, WORLD_ID, "color-floor"));
        var first = operation(PlayerStateOperation.Kind.CAPTURE, 50, 51, 52, uuid(60));
        var second = operation(PlayerStateOperation.Kind.CAPTURE, 53, 51, 54, uuid(61));
        try (var ledger = new ColorFloorWorldLedger(temporary.resolve("shared-ledger"));
             var journal = new ExternalOperationJournal(temporary.resolve("shared-journal"))) {
            var state = fixture.state(ledger, journal, fixture.regions);
            byte[] firstPayload = state.capture(first);
            byte[] secondPayload = state.capture(second);
            assertArrayEquals(firstPayload, secondPayload);
            state.enter(phase(first, PlayerStateOperation.Kind.ENTER, 55));
            state.enter(phase(second, PlayerStateOperation.Kind.ENTER, 56));
            fixture.world.setAll("minecraft:air");

            state.purge(phase(first, PlayerStateOperation.Kind.PURGE, 57));
            state.purge(phase(second, PlayerStateOperation.Kind.PURGE, 58));
            assertEquals(8, fixture.world.count("minecraft:red_concrete"));
            state.restore(phase(first, PlayerStateOperation.Kind.RESTORE, 59), 1, firstPayload);
            state.restore(phase(second, PlayerStateOperation.Kind.RESTORE, 62), 1, secondPayload);
            assertEquals(ColorFloorWorldLedger.Status.RESTORED,
                    ledger.requireLease(phase(first, PlayerStateOperation.Kind.PURGE, 63)).status());
            assertEquals(ColorFloorWorldLedger.Status.RESTORED,
                    ledger.requireLease(phase(second, PlayerStateOperation.Kind.PURGE, 64)).status());
        }
    }

    private static ColorFloorWorldManifest manifest(int cells, UUID worldId, String worldName) {
        return manifest(cells, worldId, worldName, "revision-1");
    }

    private static ColorFloorWorldManifest manifest(int cells, UUID worldId, String worldName, String revision) {
        var volume = new CuboidRegion(worldId, 0, 64, 0, cells - 1, 64, 0);
        Map<TemplateArtifact.BlockCoordinate, String> blocks = new LinkedHashMap<>();
        Map<TemplateArtifact.BlockCoordinate, String> colors = new LinkedHashMap<>();
        for (int x = 0; x < cells; x++) {
            var coordinate = new TemplateArtifact.BlockCoordinate(x, 64, 0);
            blocks.put(coordinate, "minecraft:red_concrete");
            colors.put(coordinate, FloorColor.RED.name());
        }
        var unsigned = new TemplateArtifact("immutable-floor-template", revision, worldId, worldName,
                volume, "0".repeat(64), blocks, colors);
        var artifact = new TemplateArtifact(unsigned.artifactId(), unsigned.revision(), unsigned.worldId(),
                unsigned.worldName(), unsigned.volume(), unsigned.calculateChecksum(), blocks, colors);
        var boundary = new ProtectedRegion("color-floor.facility-boundary", GameKey.COLOR_FLOOR,
                new CuboidRegion(worldId, -1, 0, -1, cells, 100, 1),
                ProtectedRegionRole.PARTICIPANT_ONLY, true);
        return new ColorFloorWorldManifest("Paper 26.2 test", boundary, artifact);
    }

    private static ProtectedRegion boundary(ColorFloorWorldManifest manifest, int maxX) {
        var bounds = manifest.boundary().bounds();
        return new ProtectedRegion(manifest.boundary().id(), GameKey.COLOR_FLOOR,
                new CuboidRegion(bounds.worldId(), bounds.minX(), bounds.minY(), bounds.minZ(), maxX,
                        bounds.maxY(), bounds.maxZ()), ProtectedRegionRole.PARTICIPANT_ONLY, true);
    }

    private static PlayerStateOperation operation(PlayerStateOperation.Kind kind, long operationId,
            long matchId, long sessionId, UUID playerId) {
        UUID captureId = uuid(sessionId + 100);
        UUID id = kind == PlayerStateOperation.Kind.CAPTURE ? captureId : uuid(operationId);
        return new PlayerStateOperation(kind, id, captureId, uuid(sessionId + 200), uuid(sessionId),
                uuid(matchId), playerId, CONNECTION_ID, CONNECTION_ID, GameKey.COLOR_FLOOR, CAPTURED_AT);
    }

    private static PlayerStateOperation phase(PlayerStateOperation capture, PlayerStateOperation.Kind kind,
            long operationId) {
        UUID connection = kind == PlayerStateOperation.Kind.ENTER ? capture.capturedConnectionId() : uuid(700 + operationId);
        return new PlayerStateOperation(kind, uuid(operationId), capture.captureOperationId(), capture.snapshotId(),
                capture.sessionId(), capture.matchId(), capture.playerId(), capture.capturedConnectionId(),
                connection, capture.game(), capture.capturedAt());
    }

    private static UUID uuid(long value) { return new UUID(0, value); }

    private static void installBukkitServer(Server server) {
        try {
            Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not install bounded Bukkit server proxy", failure);
        }
    }

    private static final class Fixture {
        private final AtomicReference<ColorFloorWorldManifest> currentManifest;
        private final WorldProxy world;
        private final Server server;
        private final ProtectedRegionRegistry regions;

        private Fixture(ColorFloorWorldManifest manifest) {
            currentManifest = new AtomicReference<>(manifest);
            world = new WorldProxy(manifest.worldId(), manifest.artifact().worldName(), manifest.artifact().blockData());
            regions = new ProtectedRegionRegistry();
            regions.register(manifest.boundary());
            server = proxy(Server.class, (instance, method, args) -> switch (method.getName()) {
                case "getWorld" -> ((UUID) args[0]).equals(world.id) ? world.proxy : null;
                case "isPrimaryThread" -> true;
                case "createBlockData" -> blockData((String) args[0]);
                case "getVersion" -> "Paper 26.2 test fixture";
                default -> defaultValue(method.getReturnType());
            });
            ColorFloorWorldStateTest.installBukkitServer(server);
        }

        private ColorFloorWorldState state(ColorFloorWorldLedger ledger, ExternalOperationJournal journal,
                ProtectedRegionRegistry activeRegions) {
            return new ColorFloorWorldState("color-floor-test", server, activeRegions, ledger, journal,
                    new RecordingAudit(), currentManifest::get);
        }
    }

    private static final class WorldProxy {
        private final UUID id;
        private final String name;
        private final Map<String, Cell> blocks = new LinkedHashMap<>();
        private final Map<String, Boolean> loadedChunks = new LinkedHashMap<>();
        private final AtomicInteger blockReads = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicInteger chunkLoads = new AtomicInteger();
        private final World proxy;

        private WorldProxy(UUID id, String name, Map<TemplateArtifact.BlockCoordinate, String> template) {
            this.id = id;
            this.name = name;
            template.forEach((coordinate, data) -> set(coordinate.x(), coordinate.y(), coordinate.z(), data));
            proxy = proxy(World.class, (instance, method, args) -> switch (method.getName()) {
                case "getUID" -> id;
                case "getName" -> name;
                case "getMinHeight" -> 0;
                case "getMaxHeight" -> 256;
                case "isChunkLoaded" -> loadedChunks.getOrDefault(args[0] + ":" + args[1], true);
                case "loadChunk" -> { chunkLoads.incrementAndGet(); yield true; }
                case "getBlockAt" -> {
                    blockReads.incrementAndGet();
                    yield cell((int) args[0], (int) args[1], (int) args[2]).proxy;
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private Cell cell(int x, int y, int z) {
            return blocks.computeIfAbsent(key(x, y, z), ignored -> new Cell(x, y, z, "minecraft:air", writes));
        }

        private void set(int x, int y, int z, String data) { cell(x, y, z).data = blockData(data); }
        private BlockData get(int x, int y, int z) { return cell(x, y, z).data; }
        private void setAll(String value) { blocks.values().forEach(cell -> cell.data = blockData(value)); }
        private int count(String value) {
            return (int) blocks.values().stream().filter(cell -> cell.data.getAsString().equals(value)).count();
        }
        private void setChunkLoaded(int x, int z, boolean loaded) { loadedChunks.put(x + ":" + z, loaded); }
        private static String key(int x, int y, int z) { return x + ":" + y + ":" + z; }
    }

    private static final class Cell {
        private final Block proxy;
        private BlockData data;

        private Cell(int x, int y, int z, String initial, AtomicInteger writes) {
            data = blockData(initial);
            proxy = proxy(Block.class, (instance, method, args) -> switch (method.getName()) {
                case "getX" -> x;
                case "getY" -> y;
                case "getZ" -> z;
                case "getBlockData" -> data;
                case "setBlockData" -> {
                    data = ((BlockData) args[0]).clone();
                    writes.incrementAndGet();
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    private static BlockData blockData(String source) {
        return proxy(BlockData.class, (instance, method, args) -> switch (method.getName()) {
            case "getAsString" -> source;
            case "clone" -> blockData(source);
            case "equals" -> args[0] instanceof BlockData other && source.equals(other.getAsString());
            case "hashCode" -> source.hashCode();
            case "toString" -> source;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static final class RecordingAudit implements AuditRepository {
        @Override public boolean append(AuditEvent event) { return true; }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
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
}
