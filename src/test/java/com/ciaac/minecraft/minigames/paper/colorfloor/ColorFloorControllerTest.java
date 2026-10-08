package com.ciaac.minecraft.minigames.paper.colorfloor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.colorfloor.ColorFloorConfig;
import com.ciaac.minecraft.minigames.colorfloor.ColorFloorGame;
import com.ciaac.minecraft.minigames.colorfloor.ColorFloorPhase;
import com.ciaac.minecraft.minigames.colorfloor.FloorColor;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.InMemorySnapshotRepository;
import com.ciaac.minecraft.minigames.isolation.WorldRecoveryPendingException;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.AuthenticatedSession;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.OperationIds;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

class ColorFloorControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
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
    void activationNotAppliedRecoversSessionAndDoesNotJoinGame() {
        UUID worldId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        World world = world(worldId);
        Location start = new Location(world, 0.5, 65, 0.5);
        ColorFloorCell cell = new ColorFloorCell(0, 64, 0);
        ColorFloorConfig config = new ColorFloorConfig(1, 2, 1, Duration.ofSeconds(1), 7,
                "test-v1", IsolationPolicy.strictNoProgress(), List.of(FloorColor.RED, FloorColor.BLUE));
        BlockData blockData = blockData();
        ColorFloorPaperSettings settings = new ColorFloorPaperSettings(true, config, world,
                "color-floor-boundary", start, Map.of(cell, FloorColor.RED), Map.of(cell, blockData));
        SessionRegistry sessions = new SessionRegistry();
        MemorySessionRepository repository = new MemorySessionRepository();
        AuthenticationRegistry authentication = new AuthenticationRegistry();
        authentication.authenticated(new AuthenticatedSession(UUID.randomUUID(), playerId,
                connectionId, NOW.minusSeconds(1), NOW.plusSeconds(600)));
        SessionCoordinator coordinator = new SessionCoordinator(authentication, sessions, repository,
                new InMemorySnapshotRepository(), new TestPlayerStateGateway(),
                IsolationPolicy.strictNoProgress(), new SnapshotEnvelopeCodec(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        AdmissionRequest request = new AdmissionRequest(requestId, sessionId, snapshotId, matchId,
                playerId, connectionId, GameKey.COLOR_FLOOR, NOW);
        UUID activationOperation = OperationIds.derive(requestId, "GAME_ACTIVE");
        Player player = player(playerId, () -> coordinator.activate(sessionId, activationOperation, NOW));
        ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
        regions.register(new ProtectedRegion("color-floor-boundary", GameKey.COLOR_FLOOR,
                new CuboidRegion(worldId, -2, 60, -2, 2, 70, 2),
                ProtectedRegionRole.PARTICIPANT_ONLY, true));
        RegionAdmissionRegistry admissions = new RegionAdmissionRegistry();
        ColorFloorGame game = new ColorFloorGame(matchId, config);
        ColorFloorController controller = new ColorFloorController(matchId, game, settings,
                coordinator, sessions, admissions, regions,
                Clock.fixed(NOW, ZoneOffset.UTC), StatisticsResultSink.unavailable());
        RegionAdmissionToken token = new RegionAdmissionToken(UUID.randomUUID(), sessionId,
                playerId, "color-floor-boundary", NOW, NOW.plusSeconds(60));

        AdmissionResult result = controller.join(player, request, token);

        assertEquals(AdmissionStatus.REJECTED, result.status());
        assertEquals("JOIN_FAILED", result.code());
        assertFalse(admissions.permits(playerId, sessionId, "color-floor-boundary", NOW));
        assertEquals(ColorFloorPhase.WAITING, game.phase());
        assertEquals(SessionPhase.CLOSED, repository.find(sessionId).orElseThrow().phase());
        assertThrows(IllegalStateException.class,
                () -> game.start(new com.ciaac.minecraft.minigames.hotpotato.OperationId(matchId, 100)));
    }

    @Test
    void pendingRecoveryRetriesOnNextTickAndClosesTheSession() {
        Harness harness = new Harness(1, session -> true);
        PlayerSession session = harness.join(100);

        assertTrue(harness.controller.onDeath(session.playerId(), UUID.randomUUID()));

        assertEquals(1, harness.gateway.restoreCalls);
        assertEquals(SessionPhase.RECOVERING, session.phase());
        harness.controller.tick(NOW.plusSeconds(1));
        assertEquals(2, harness.gateway.restoreCalls);
        assertEquals(List.of(session.playerId()), harness.gateway.restoredPlayers);
        assertEquals(SessionPhase.CLOSED, session.phase());
        assertTrue(harness.sessions.findByPlayer(session.playerId()).isEmpty());
    }

    @Test
    void pluginTeleportsAreDeniedDuringActivePlayButAllowedForCoordinatorRecovery() {
        Harness harness = new Harness(0, session -> true);
        PlayerSession session = harness.join(105);
        Player player = player(session.playerId(), () -> {});

        PlayerTeleportEvent activePluginTeleport = teleport(player, harness.world,
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        harness.controller.onTeleport(activePluginTeleport);
        assertTrue(activePluginTeleport.isCancelled());

        PlayerTeleportEvent activeCommandTeleport = teleport(player, harness.world,
                PlayerTeleportEvent.TeleportCause.COMMAND);
        harness.controller.onTeleport(activeCommandTeleport);
        assertTrue(activeCommandTeleport.isCancelled());

        harness.gateway.restoreHook = () -> {
            assertEquals(SessionPhase.RECOVERING, session.phase());
            PlayerTeleportEvent restorePluginTeleport = teleport(player, harness.world,
                    PlayerTeleportEvent.TeleportCause.PLUGIN);
            harness.controller.onTeleport(restorePluginTeleport);
            assertFalse(restorePluginTeleport.isCancelled());

            PlayerTeleportEvent cancelledRestoreTeleport = teleport(player, harness.world,
                    PlayerTeleportEvent.TeleportCause.PLUGIN);
            cancelledRestoreTeleport.setCancelled(true);
            harness.controller.onTeleport(cancelledRestoreTeleport);
            assertTrue(cancelledRestoreTeleport.isCancelled());

            PlayerTeleportEvent nonPluginRecoveryTeleport = teleport(player, harness.world,
                    PlayerTeleportEvent.TeleportCause.COMMAND);
            harness.controller.onTeleport(nonPluginRecoveryTeleport);
            assertTrue(nonPluginRecoveryTeleport.isCancelled());
        };

        assertTrue(harness.controller.onDeath(session.playerId(), UUID.randomUUID()));
        assertEquals(SessionPhase.CLOSED, session.phase());
    }

    @Test
    void pluginTeleportIsAllowedWhileCoordinatorIsRestoringNormally() {
        Harness harness = new Harness(0, session -> true);
        PlayerSession session = harness.join(106);
        Player player = player(session.playerId(), () -> {});
        PlayerTeleportEvent[] duringRestore = new PlayerTeleportEvent[1];
        harness.gateway.restoreHook = () -> {
            assertEquals(SessionPhase.RESTORING, session.phase());
            duringRestore[0] = teleport(player, harness.world, PlayerTeleportEvent.TeleportCause.PLUGIN);
            harness.controller.onTeleport(duringRestore[0]);
        };

        AdmissionResult result = harness.coordinator.finishAndRestore(
                session.sessionId(), UUID.randomUUID(), "TEST_RESTORE");

        assertEquals(AdmissionStatus.RECOVERED, result.status());
        assertFalse(duringRestore[0].isCancelled());
        assertEquals(SessionPhase.CLOSED, session.phase());
    }

    @Test
    void disconnectSkipsDepartingParticipantButRestoresConnectedOpponent() {
        Harness harness = new Harness(0, session -> true);
        PlayerSession departing = harness.join(110);
        PlayerSession opponent = harness.join(111);

        harness.controller.onDisconnect(departing.playerId(), UUID.randomUUID());

        assertEquals(List.of(opponent.playerId()), harness.gateway.restoredPlayers);
        assertEquals(SessionPhase.ACTIVE, departing.phase());
        assertEquals(SessionPhase.CLOSED, opponent.phase());
        assertTrue(harness.sessions.findByPlayer(departing.playerId()).isPresent());
        assertTrue(harness.sessions.findByPlayer(opponent.playerId()).isEmpty());
    }

    @Test
    void restorationReadinessFalseKeepsSessionUnchangedAndSkipsGatewayRestore() {
        Harness harness = new Harness(0, session -> false);
        PlayerSession session = harness.join(120);

        assertTrue(harness.controller.onDeath(session.playerId(), UUID.randomUUID()));

        assertEquals(0, harness.gateway.restoreCalls);
        assertEquals(SessionPhase.ACTIVE, session.phase());
        assertTrue(harness.sessions.findByPlayer(session.playerId()).isPresent());
        assertFalse(harness.controller.status().admissionReady());
    }

    @Test
    void leaveSurfacesPendingRecoveryInsteadOfClaimingStateWasRestored() {
        Harness harness = new Harness(1, session -> true);
        PlayerSession session = harness.join(130);

        AdmissionResult result = harness.controller.leave(session.playerId(), UUID.randomUUID());

        assertEquals(AdmissionStatus.REJECTED, result.status());
        assertEquals("RECOVERY_PENDING", result.code());
        assertTrue(result.messagePtPt().contains("continua protegido"));
        assertFalse(result.messagePtPt().contains("foi restaurado"));
        assertEquals(SessionPhase.RECOVERING, session.phase());
        harness.controller.tick(NOW.plusSeconds(1));
        assertEquals(SessionPhase.CLOSED, session.phase());
    }

    private static World world(UUID id) {
        return proxy(World.class, method -> switch (method.getName()) {
            case "getUID" -> id;
            case "getName" -> "synthetic-world";
            case "isChunkLoaded" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static void installBukkitServer(Server server) {
        try {
            Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not install scoped Bukkit server proxy", failure);
        }
    }

    private static UUID uuid(long value) { return new UUID(0L, value); }

    private static PlayerTeleportEvent teleport(Player player, World world,
            PlayerTeleportEvent.TeleportCause cause) {
        return new PlayerTeleportEvent(player, new Location(world, 0.5, 65, 0.5),
                new Location(world, 1.5, 65, 1.5), cause);
    }

    private static Player player(UUID id, Runnable onTeleport) {
        return proxy(Player.class, method -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "isOnline", "isValid" -> true;
            case "teleport" -> {
                onTeleport.run();
                yield true;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private static BlockData blockData() {
        BlockData[] holder = new BlockData[1];
        holder[0] = proxy(BlockData.class, method -> method.getName().equals("clone")
                ? holder[0] : defaultValue(method.getReturnType()));
        return holder[0];
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

    private static final class MemorySessionRepository implements SessionRepository {
        private final Map<UUID, PlayerSession> sessions = new HashMap<>();
        @Override public boolean save(PlayerSession session) {
            sessions.put(session.sessionId(), session);
            return true;
        }
        @Override public Optional<PlayerSession> find(UUID sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }
        @Override public List<PlayerSession> nonTerminal() {
            return sessions.values().stream().filter(session -> !session.phase().terminal()).toList();
        }
    }

    private static final class TestPlayerStateGateway implements PlayerStateGateway {
        @Override public java.util.Set<PlayerStateFacet> supportedFacets() {
            return EnumSet.allOf(PlayerStateFacet.class);
        }
        @Override public PlayerStateSnapshot capture(UUID snapshotId, UUID operationId, UUID sessionId,
                UUID matchId, UUID playerId, UUID connectionId, GameKey game, Instant capturedAt) {
            return new PlayerStateSnapshot(2, snapshotId, operationId, sessionId, matchId,
                    playerId, connectionId, game, capturedAt, Map.of());
        }
        @Override public void enterTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {
            // This controller regression exercises activation and session recovery only.
        }
        @Override public void purgeTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {
            // This controller regression exercises activation and session recovery only.
        }
        @Override public void restore(UUID operationId, PlayerStateSnapshot snapshot) {
            // This controller regression exercises activation and session recovery only.
        }
    }

    private static final class RetryingPlayerStateGateway implements PlayerStateGateway {
        private int pendingRestores;
        private int restoreCalls;
        private final List<UUID> restoredPlayers = new ArrayList<>();
        private Runnable restoreHook = () -> {};

        private RetryingPlayerStateGateway(int pendingRestores) {
            this.pendingRestores = pendingRestores;
        }

        @Override public Set<PlayerStateFacet> supportedFacets() { return EnumSet.allOf(PlayerStateFacet.class); }

        @Override public PlayerStateSnapshot capture(UUID snapshotId, UUID operationId, UUID sessionId,
                UUID matchId, UUID playerId, UUID connectionId, GameKey game, Instant capturedAt) {
            return new PlayerStateSnapshot(2, snapshotId, operationId, sessionId, matchId,
                    playerId, connectionId, game, capturedAt, Map.of());
        }

        @Override public void enterTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {}
        @Override public void purgeTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {}

        @Override public void restore(UUID operationId, PlayerStateSnapshot snapshot) {
            restoreCalls++;
            restoreHook.run();
            if (pendingRestores > 0) {
                pendingRestores--;
                throw new WorldRecoveryPendingException();
            }
            restoredPlayers.add(snapshot.playerId());
        }
    }

    private final class Harness {
        private final UUID matchId = UUID.randomUUID();
        private final UUID worldId = UUID.randomUUID();
        private final World world = world(worldId);
        private final ColorFloorConfig config = new ColorFloorConfig(1, 4, 2, Duration.ofSeconds(1),
                17, "test-v1", IsolationPolicy.strictNoProgress(), List.of(FloorColor.RED, FloorColor.BLUE));
        private final SessionRegistry sessions = new SessionRegistry();
        private final MemorySessionRepository repository = new MemorySessionRepository();
        private final AuthenticationRegistry authentication = new AuthenticationRegistry();
        private final RetryingPlayerStateGateway gateway;
        private final SessionCoordinator coordinator;
        private final ColorFloorController controller;

        private Harness(int pendingRestores, Predicate<PlayerSession> restorationReady) {
            installBukkitServer(proxy(Server.class, method -> switch (method.getName()) {
                case "isPrimaryThread" -> true;
                default -> defaultValue(method.getReturnType());
            }));
            gateway = new RetryingPlayerStateGateway(pendingRestores);
            coordinator = new SessionCoordinator(authentication, sessions, repository,
                    new InMemorySnapshotRepository(), gateway, IsolationPolicy.strictNoProgress(),
                    new SnapshotEnvelopeCodec(), Clock.fixed(NOW, ZoneOffset.UTC));
            ColorFloorPaperSettings settings = new ColorFloorPaperSettings(true, config, world,
                    "color-floor-boundary", new Location(world, 0.5, 65, 0.5),
                    Map.of(new ColorFloorCell(0, 64, 0), FloorColor.RED),
                    Map.of(new ColorFloorCell(0, 64, 0), blockData()));
            ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
            regions.register(new ProtectedRegion("color-floor-boundary", GameKey.COLOR_FLOOR,
                    new CuboidRegion(worldId, -2, 60, -2, 2, 70, 2),
                    ProtectedRegionRole.PARTICIPANT_ONLY, true));
            controller = new ColorFloorController(matchId, new ColorFloorGame(matchId, config), settings,
                    coordinator, sessions, new RegionAdmissionRegistry(), regions,
                    Clock.fixed(NOW, ZoneOffset.UTC), StatisticsResultSink.unavailable(), restorationReady);
        }

        private PlayerSession join(long seed) {
            UUID playerId = uuid(seed);
            UUID connectionId = uuid(seed + 1_000);
            UUID requestId = uuid(seed + 2_000);
            UUID sessionId = uuid(seed + 3_000);
            UUID snapshotId = uuid(seed + 4_000);
            authentication.authenticated(new AuthenticatedSession(uuid(seed + 5_000), playerId,
                    connectionId, NOW.minusSeconds(1), NOW.plusSeconds(600)));
            AdmissionRequest request = new AdmissionRequest(requestId, sessionId, snapshotId,
                    matchId, playerId, connectionId, GameKey.COLOR_FLOOR, NOW);
            RegionAdmissionToken token = new RegionAdmissionToken(uuid(seed + 6_000), sessionId,
                    playerId, "color-floor-boundary", NOW, NOW.plusSeconds(60));
            AdmissionResult result = controller.join(player(playerId, () -> {}), request, token);
            assertEquals(AdmissionStatus.PREPARED, result.status());
            return result.session().orElseThrow();
        }
    }

    @FunctionalInterface private interface Invocation {
        Object invoke(java.lang.reflect.Method method) throws Throwable;
    }
}
