package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.persistence.AuditEvent;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

class ImmutableMinigameWorldStateTest {
    private static final Instant CAPTURED_AT = Instant.parse("2026-10-07T11:12:13.123456789Z");
    private static final UUID WORLD_ID = uuid(1);
    private static final UUID PLAYER_ID = uuid(2);
    private static final UUID CAPTURE_EPOCH = uuid(3);

    @TempDir Path temporary;

    @BeforeEach
    void useCanonicalTemporaryDirectory() throws IOException {
        temporary = temporary.toRealPath();
    }

    @Test
    void captureAndCheckpointsRemainByteEquivalentAcrossJournalReopenAndReconnect() {
        var regions = reviewedRegions();
        var server = server(Map.of(WORLD_ID, world(WORLD_ID, -64, 320)), true);
        var audit = new RecordingAudit();
        byte[] hotPotato;
        byte[] sumo;
        PlayerStateOperation hpCapture = capture(GameKey.HOT_POTATO);
        PlayerStateOperation sumoCapture = capture(GameKey.KNOCKBACK_SUMO);

        try (var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var state = new ImmutableMinigameWorldState("immutable-test", server, regions, journal, audit);
            hotPotato = state.capture(hpCapture);
            sumo = state.capture(sumoCapture);
            assertTrue(hotPotato.length > 0);
            assertTrue(sumo.length > 0);
            assertFalse(java.util.Arrays.equals(hotPotato, sumo));
            state.checkpoint(phase(hpCapture, PlayerStateOperation.Kind.ENTER, uuid(30), CAPTURE_EPOCH));
            state.checkpoint(phase(hpCapture, PlayerStateOperation.Kind.PURGE, uuid(31), uuid(40)));
        }

        try (var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var state = new ImmutableMinigameWorldState("immutable-test", server, regions, journal, audit);
            assertArrayEquals(hotPotato, state.capture(hpCapture));
            assertArrayEquals(sumo, state.capture(sumoCapture));
            PlayerStateOperation reconnectRestore = phase(
                    hpCapture, PlayerStateOperation.Kind.RESTORE, uuid(32), uuid(41));
            state.validate(reconnectRestore, 1, hotPotato);
            state.checkpoint(reconnectRestore);
            state.checkpoint(phase(
                    hpCapture, PlayerStateOperation.Kind.RESTORE, uuid(32), uuid(42)));
        }

        assertEquals(5, audit.events.size());
        assertTrue(audit.events.stream().allMatch(event -> event.outcomeCode().equals("UNCHANGED")));
    }

    @Test
    void validateRejectsVersionPayloadAndEveryCapturedIdentityConflict() {
        var regions = reviewedRegions();
        var audit = new RecordingAudit();
        try (var journal = new ExternalOperationJournal(temporary.resolve("identity-journal"))) {
            var state = new ImmutableMinigameWorldState("immutable-test",
                    server(Map.of(WORLD_ID, world(WORLD_ID, -64, 320)), true), regions, journal, audit);
            PlayerStateOperation capture = capture(GameKey.HOT_POTATO);
            byte[] payload = state.capture(capture);
            PlayerStateOperation restore = phase(capture, PlayerStateOperation.Kind.RESTORE, uuid(50), uuid(51));

            assertThrows(IllegalArgumentException.class, () -> state.validate(restore, 2, payload));
            assertThrows(IllegalArgumentException.class, () -> state.validate(restore, 1, null));
            assertThrows(IllegalArgumentException.class, () -> state.validate(restore, 1, new byte[0]));
            byte[] changedPayload = payload.clone();
            changedPayload[changedPayload.length - 1] ^= 1;
            assertThrows(IllegalStateException.class, () -> state.validate(restore, 1, changedPayload));

            assertThrows(IllegalStateException.class,
                    () -> state.validate(copy(restore, uuid(90), restore.sessionId(), restore.matchId(),
                            restore.playerId(), restore.capturedConnectionId(), restore.game(), restore.capturedAt()), 1, payload));
            assertThrows(IllegalStateException.class,
                    () -> state.validate(copy(restore, restore.captureOperationId(), uuid(91), restore.matchId(),
                            restore.playerId(), restore.capturedConnectionId(), restore.game(), restore.capturedAt()), 1, payload));
            assertThrows(IllegalStateException.class,
                    () -> state.validate(copy(restore, restore.captureOperationId(), restore.sessionId(), uuid(92),
                            restore.playerId(), restore.capturedConnectionId(), restore.game(), restore.capturedAt()), 1, payload));
            assertThrows(IllegalStateException.class,
                    () -> state.validate(copy(restore, restore.captureOperationId(), restore.sessionId(), restore.matchId(),
                            uuid(93), restore.capturedConnectionId(), restore.game(), restore.capturedAt()), 1, payload));
            assertThrows(IllegalStateException.class,
                    () -> state.validate(copy(restore, restore.captureOperationId(), restore.sessionId(), restore.matchId(),
                            restore.playerId(), uuid(94), restore.game(), restore.capturedAt()), 1, payload));
            assertThrows(IllegalStateException.class,
                    () -> state.validate(copy(restore, restore.captureOperationId(), restore.sessionId(), restore.matchId(),
                            restore.playerId(), restore.capturedConnectionId(), GameKey.KNOCKBACK_SUMO,
                            restore.capturedAt()), 1, payload));
            assertThrows(IllegalStateException.class,
                    () -> state.validate(copy(restore, restore.captureOperationId(), restore.sessionId(), restore.matchId(),
                            restore.playerId(), restore.capturedConnectionId(), restore.game(),
                            restore.capturedAt().plusNanos(1)), 1, payload));
        }
    }

    @Test
    void captureReplayRejectsConflictingSnapshotIdentityAndCurrentPolicyDrift() {
        var audit = new RecordingAudit();
        var originalRegions = reviewedRegions();
        PlayerStateOperation capture = capture(GameKey.HOT_POTATO);
        try (var journal = new ExternalOperationJournal(temporary.resolve("capture-conflict"))) {
            var server = server(Map.of(WORLD_ID, world(WORLD_ID, -64, 320)), true);
            var state = new ImmutableMinigameWorldState("immutable-test", server, originalRegions, journal, audit);
            byte[] payload = state.capture(capture);

            PlayerStateOperation wrongSnapshot = new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE,
                    capture.operationId(), capture.captureOperationId(), uuid(95), capture.sessionId(),
                    capture.matchId(), capture.playerId(), capture.capturedConnectionId(), capture.connectionId(),
                    capture.game(), capture.capturedAt());
            assertThrows(IllegalStateException.class, () -> state.capture(wrongSnapshot));

            var changedBounds = regionsWithHotPotato("hot-potato.arena", ProtectedRegionRole.GAME_WORLD_BOUNDARY,
                    true, new CuboidRegion(WORLD_ID, 200, -64, 200, 301, 319, 301));
            var changedState = new ImmutableMinigameWorldState("immutable-test", server, changedBounds, journal, audit);
            assertThrows(IllegalStateException.class, () -> changedState.capture(capture));
            assertThrows(IllegalStateException.class, () -> changedState.validate(
                    phase(capture, PlayerStateOperation.Kind.RESTORE, uuid(60), uuid(61)), 1, payload));

            var changedHeight = new ImmutableMinigameWorldState("immutable-test",
                    server(Map.of(WORLD_ID, world(WORLD_ID, -64, 319)), true), originalRegions, journal, audit);
            assertThrows(IllegalStateException.class, () -> changedHeight.validate(
                    phase(capture, PlayerStateOperation.Kind.RESTORE, uuid(62), uuid(63)), 1, payload));
        }
    }

    @Test
    void malformedBoundaryWorldRoleIdAndImmutabilityAreRejected() {
        int index = 0;
        for (ProtectedRegion invalid : List.of(
                region(GameKey.HOT_POTATO, "hot-potato.arena", ProtectedRegionRole.GAME_WORLD_BOUNDARY,
                        false, new CuboidRegion(WORLD_ID, 200, -64, 200, 300, 319, 300)),
                region(GameKey.HOT_POTATO, "wrong-id", ProtectedRegionRole.GAME_WORLD_BOUNDARY,
                        true, new CuboidRegion(WORLD_ID, 200, -64, 200, 300, 319, 300)),
                region(GameKey.HOT_POTATO, "hot-potato.arena", ProtectedRegionRole.PARTICIPANT_ONLY,
                        true, new CuboidRegion(WORLD_ID, 200, -64, 200, 300, 319, 300)),
                region(GameKey.HOT_POTATO, "hot-potato.arena", ProtectedRegionRole.GAME_WORLD_BOUNDARY,
                        true, new CuboidRegion(WORLD_ID, 200, -65, 200, 300, 319, 300)),
                region(GameKey.HOT_POTATO, "hot-potato.arena", ProtectedRegionRole.GAME_WORLD_BOUNDARY,
                        true, new CuboidRegion(WORLD_ID, 200, -64, 200, 300, 320, 300)))) {
            var regions = new ProtectedRegionRegistry();
            regions.register(invalid);
            try (var journal = new ExternalOperationJournal(temporary.resolve("invalid-" + index++))) {
                var state = new ImmutableMinigameWorldState("immutable-test",
                        server(Map.of(WORLD_ID, world(WORLD_ID, -64, 320)), true), regions, journal,
                        new RecordingAudit());
                assertThrows(IllegalStateException.class, () -> state.capture(capture(GameKey.HOT_POTATO)));
            }
        }

        var duplicate = reviewedRegions();
        duplicate.register(region(GameKey.HOT_POTATO, "hot-potato.arena.extra",
                ProtectedRegionRole.GAME_WORLD_BOUNDARY, true,
                new CuboidRegion(WORLD_ID, 400, -64, 400, 500, 319, 500)));
        try (var journal = new ExternalOperationJournal(temporary.resolve("duplicate-region"))) {
            var state = new ImmutableMinigameWorldState("immutable-test",
                    server(Map.of(WORLD_ID, world(WORLD_ID, -64, 320)), true), duplicate, journal,
                    new RecordingAudit());
            assertThrows(IllegalStateException.class, () -> state.capture(capture(GameKey.HOT_POTATO)));
        }

        var missingWorld = new ProtectedRegionRegistry();
        missingWorld.register(region(GameKey.HOT_POTATO, "hot-potato.arena",
                ProtectedRegionRole.GAME_WORLD_BOUNDARY, true,
                new CuboidRegion(uuid(999), 200, -64, 200, 300, 319, 300)));
        try (var journal = new ExternalOperationJournal(temporary.resolve("missing-world"))) {
            var state = new ImmutableMinigameWorldState("immutable-test",
                    server(Map.of(WORLD_ID, world(WORLD_ID, -64, 320)), true), missingWorld, journal,
                    new RecordingAudit());
            assertThrows(IllegalStateException.class, () -> state.capture(capture(GameKey.HOT_POTATO)));
        }
    }

    @Test
    void arenaPortRejectsOffThreadAndClosedLifecycleBeforePlayerOrJournalAccess() {
        var regions = reviewedRegions();
        ServerState serverState = new ServerState(Map.of(WORLD_ID, world(WORLD_ID, -64, 320)), true);
        Server server = proxy(Server.class, serverState::invoke);
        var audit = new RecordingAudit();
        Path ledgerPath = temporary.resolve("port-ledger");
        Path journalPath = temporary.resolve("port-journal");
        try (var ledger = new ArenaWorldLedger(ledgerPath);
             var journal = new ExternalOperationJournal(journalPath)) {
            Plugin plugin = proxy(Plugin.class, (instance, method, args) -> switch (method.getName()) {
                case "getName" -> "CIAACPlatform";
                case "namespace" -> "ciaacplatform";
                default -> defaultValue(method.getReturnType());
            });
            var ownership = new ArenaProjectileOwnership(plugin, ledger);
            ArenaWorldStatePort port = new ArenaWorldStatePort(
                    server, regions, ledger, ownership, journal, audit);
            AtomicInteger reads = new AtomicInteger();
            Player player = player(PLAYER_ID, reads);
            assertThrows(IllegalStateException.class, port::lifecycleReady,
                    "unit classpath must not claim the reviewed native Paper build");
            Server offThreadServer = server(Map.of(WORLD_ID, world(WORLD_ID, -64, 320)), false);
            var offThreadPort = new ArenaWorldStatePort(offThreadServer, regions, ledger, ownership, journal, audit);
            IllegalStateException offThread = assertThrows(IllegalStateException.class,
                    () -> offThreadPort.capture(player, capture(GameKey.HOT_POTATO)));
            assertTrue(offThread.getMessage().contains("primary server thread"));
            IllegalStateException closed = assertThrows(IllegalStateException.class,
                    () -> port.capture(player, capture(GameKey.HOT_POTATO)));
            assertTrue(closed.getMessage().contains("unavailable"));
            assertEquals(0, reads.get());
            assertTrue(audit.events.isEmpty());
            assertFalse(port.available());
        }

        ServerState closedState = new ServerState(Map.of(WORLD_ID, world(WORLD_ID, -64, 320)), true);
        Server closedServer = proxy(Server.class, closedState::invoke);
        try (var ledger = new ArenaWorldLedger(temporary.resolve("closed-ledger"));
             var journal = new ExternalOperationJournal(temporary.resolve("closed-journal"))) {
            Plugin plugin = proxy(Plugin.class, (instance, method, args) -> switch (method.getName()) {
                case "getName" -> "CIAACPlatform";
                case "namespace" -> "ciaacplatform";
                default -> defaultValue(method.getReturnType());
            });
            var port = new ArenaWorldStatePort(closedServer, regions, ledger,
                    new ArenaProjectileOwnership(plugin, ledger), journal, audit);
            AtomicInteger reads = new AtomicInteger();
            IllegalStateException closed = assertThrows(IllegalStateException.class,
                    () -> port.capture(player(PLAYER_ID, reads), capture(GameKey.HOT_POTATO)));
            assertTrue(closed.getMessage().contains("unavailable"));
            assertEquals(0, reads.get());
            assertFalse(port.available());
            assertTrue(audit.events.isEmpty());
        }
    }

    private static ProtectedRegionRegistry reviewedRegions() {
        var regions = new ProtectedRegionRegistry();
        regions.register(region(GameKey.HOT_POTATO, "hot-potato.arena",
                ProtectedRegionRole.GAME_WORLD_BOUNDARY, true,
                new CuboidRegion(WORLD_ID, 200, -64, 200, 300, 319, 300)));
        regions.register(region(GameKey.KNOCKBACK_SUMO, "knockback-sumo.boundary",
                ProtectedRegionRole.PARTICIPANT_ONLY, true,
                new CuboidRegion(WORLD_ID, 0, -64, 0, 100, 319, 100)));
        return regions;
    }

    private static ProtectedRegionRegistry regionsWithHotPotato(String id, ProtectedRegionRole role,
            boolean immutable, CuboidRegion bounds) {
        var regions = new ProtectedRegionRegistry();
        regions.register(region(GameKey.HOT_POTATO, id, role, immutable, bounds));
        return regions;
    }

    private static ProtectedRegion region(GameKey game, String id, ProtectedRegionRole role,
            boolean immutable, CuboidRegion bounds) {
        return new ProtectedRegion(id, game, bounds, role, immutable);
    }

    private static PlayerStateOperation capture(GameKey game) {
        UUID captureId = uuid(10 + game.ordinal());
        return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, captureId, captureId,
                uuid(20 + game.ordinal()), uuid(30 + game.ordinal()), uuid(40 + game.ordinal()),
                PLAYER_ID, CAPTURE_EPOCH, CAPTURE_EPOCH, game, CAPTURED_AT);
    }

    private static PlayerStateOperation phase(PlayerStateOperation capture, PlayerStateOperation.Kind kind,
            UUID operationId, UUID currentEpoch) {
        UUID connection = kind == PlayerStateOperation.Kind.ENTER
                ? capture.capturedConnectionId() : currentEpoch;
        return new PlayerStateOperation(kind, operationId, capture.captureOperationId(), capture.snapshotId(),
                capture.sessionId(), capture.matchId(), capture.playerId(), capture.capturedConnectionId(),
                connection, capture.game(), capture.capturedAt());
    }

    private static PlayerStateOperation copy(PlayerStateOperation source, UUID captureOperationId,
            UUID sessionId, UUID matchId, UUID playerId, UUID capturedConnectionId, GameKey game, Instant capturedAt) {
        return new PlayerStateOperation(source.kind(), source.operationId(), captureOperationId, source.snapshotId(),
                sessionId, matchId, playerId, capturedConnectionId, source.connectionId(), game, capturedAt);
    }

    private static Player player(UUID id, AtomicInteger reads) {
        return proxy(Player.class, (instance, method, args) -> {
            if (method.getName().equals("getUniqueId")) {
                reads.incrementAndGet();
                return id;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static World world(UUID id, int minHeight, int maxHeight) {
        return proxy(World.class, (instance, method, args) -> switch (method.getName()) {
            case "getUID" -> id;
            case "getMinHeight" -> minHeight;
            case "getMaxHeight" -> maxHeight;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Server server(Map<UUID, World> worlds, boolean primaryThread) {
        return proxy(Server.class, (instance, method, args) -> switch (method.getName()) {
            case "getWorld" -> worlds.get(args[0]);
            case "getVersion" -> "Paper 26.2 test fixture";
            case "isPrimaryThread" -> primaryThread;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static UUID uuid(long value) { return new UUID(0L, value); }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
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

    private static final class ServerState {
        private final Map<UUID, World> worlds;
        private final boolean primaryThread;

        private ServerState(Map<UUID, World> worlds, boolean primaryThread) {
            this.worlds = worlds;
            this.primaryThread = primaryThread;
        }

        private Object invoke(Object instance, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getWorld" -> worlds.get(args[0]);
                case "getVersion" -> "Paper 26.2 test fixture";
                case "isPrimaryThread" -> primaryThread;
                case "toString" -> "test-server";
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == args[0];
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static final class RecordingAudit implements AuditRepository {
        private final List<AuditEvent> events = new ArrayList<>();
        @Override public boolean append(AuditEvent event) {
            if (events.contains(event)) return false;
            events.add(event);
            return true;
        }
    }
}
