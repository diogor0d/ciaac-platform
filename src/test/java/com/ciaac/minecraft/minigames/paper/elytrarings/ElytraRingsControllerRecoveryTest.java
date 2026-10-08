package com.ciaac.minecraft.minigames.paper.elytrarings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.elytrarings.ElytraCourseRevision;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsConfig;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsGame;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsPhase;
import com.ciaac.minecraft.minigames.elytrarings.RingCheckpoint;
import com.ciaac.minecraft.minigames.isolation.InMemorySnapshotRepository;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.WorldRecoveryPendingException;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
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
import com.ciaac.minecraft.minigames.runtime.OperationIds;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ElytraRingsControllerRecoveryTest {
    private static void installBukkitServer(org.bukkit.Server server) {
        try {
            var field = org.bukkit.Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
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
    void leaveKeepsTicketsAndReportsPendingUntilStableOperationRetryClosesSession() {
        Harness harness = new Harness(1, session -> true);
        PlayerSession session = harness.prepare(10);
        harness.openWaiting();
        UUID root = UUID.randomUUID();

        AdmissionResult result = harness.controller.leave(session.playerId(), root);

        assertEquals(AdmissionStatus.REJECTED, result.status());
        assertEquals("RECOVERY_PENDING", result.code());
        assertEquals(SessionPhase.RECOVERING, session.phase());
        assertEquals(1, harness.gateway.restoreCalls);
        assertTrue(harness.world.ticketCount() > 0);
        assertFalse(harness.chunkPreparation.status().phase() == ElytraChunkPreparation.Phase.RELEASED);
        UUID sessionRoot = OperationIds.derive(root, "RESTORE_" + session.playerId());
        UUID scopedRoot = OperationIds.derive(sessionRoot, "SESSION_" + session.sessionId());
        UUID expectedOperation = OperationIds.derive(scopedRoot, "SNAPSHOT_RESTORE_APPLY");
        assertEquals(expectedOperation, harness.gateway.restoreOperations.getFirst());

        harness.controller.tick(NOW.plusSeconds(1));

        assertEquals(List.of(expectedOperation, expectedOperation), harness.gateway.restoreOperations);
        assertEquals(SessionPhase.CLOSED, session.phase());
        assertEquals(ElytraRingsPhase.CLOSED, harness.game.phase());
        assertEquals(0, harness.world.ticketCount());
        assertEquals(ElytraChunkPreparation.Phase.RELEASED, harness.chunkPreparation.status().phase());
    }

    @Test
    void disconnectDefersPlayerUntilGenericRecoveryAndRetainsTickets() {
        Harness harness = new Harness(0, session -> true);
        PlayerSession departing = harness.prepare(20);
        PlayerSession opponent = harness.prepare(30);
        harness.openWaiting();

        harness.controller.onDisconnect(departing.playerId(), UUID.randomUUID());

        assertEquals(List.of(opponent.playerId()), harness.gateway.restoredPlayers);
        assertEquals(SessionPhase.ACTIVE, departing.phase());
        assertEquals(SessionPhase.CLOSED, opponent.phase());
        assertEquals(ElytraRingsPhase.RECOVERING, harness.game.phase());
        assertTrue(harness.world.ticketCount() > 0);

        AdmissionResult externalRecovery = harness.coordinator.recover(
                departing.sessionId(), UUID.randomUUID(), "AUTHENTICATED_RECONNECT");
        assertEquals(AdmissionStatus.RECOVERED, externalRecovery.status());
        harness.controller.tick(NOW.plusSeconds(1));

        assertEquals(SessionPhase.CLOSED, departing.phase());
        assertEquals(ElytraRingsPhase.CLOSED, harness.game.phase());
        assertEquals(0, harness.world.ticketCount());
    }

    @Test
    void readinessIsRecheckedAndLegacyConstructorFailsClosed() {
        boolean[] ready = {false};
        Harness harness = new Harness(0, session -> ready[0]);
        PlayerSession session = harness.prepare(40);
        harness.openWaiting();

        AdmissionResult pending = harness.controller.leave(session.playerId(), UUID.randomUUID());
        assertEquals(AdmissionStatus.REJECTED, pending.status());
        assertEquals(0, harness.gateway.restoreCalls);
        assertEquals(SessionPhase.ACTIVE, session.phase());
        assertTrue(harness.world.ticketCount() > 0);

        ready[0] = true;
        harness.controller.tick(NOW.plusSeconds(1));
        assertEquals(SessionPhase.CLOSED, session.phase());
        assertEquals(0, harness.world.ticketCount());

        Harness legacy = new Harness(0, sessionValue -> true, true);
        PlayerSession legacySession = legacy.prepare(50);
        legacy.openWaiting();
        AdmissionResult legacyPending = legacy.controller.leave(legacySession.playerId(), UUID.randomUUID());
        assertEquals(AdmissionStatus.REJECTED, legacyPending.status());
        assertEquals(0, legacy.gateway.restoreCalls);
        assertEquals(SessionPhase.ACTIVE, legacySession.phase());
    }

    @Test
    void resultFinishWaitsForSessionRestoreBeforeTicketRelease() {
        Harness harness = new Harness(1, session -> true);
        PlayerSession session = harness.prepare(999);
        harness.startRunning(session.playerId());
        harness.admissions.issue(harness.token(session));
        harness.world.players = List.of(player(session.playerId(), harness.start));
        setField(harness.controller, "startedAt", NOW);

        harness.controller.tick(NOW.plus(Duration.ofMinutes(2)));

        assertEquals(ElytraRingsPhase.FINISHING, harness.game.phase());
        assertEquals(SessionPhase.RESTORING, session.phase());
        assertEquals(1, harness.gateway.restoreCalls);
        assertTrue(harness.world.ticketCount() > 0);

        harness.controller.tick(NOW.plus(Duration.ofMinutes(2).plusSeconds(1)));

        assertEquals(SessionPhase.CLOSED, session.phase());
        assertEquals(ElytraRingsPhase.CLOSED, harness.game.phase());
        assertEquals(2, harness.gateway.restoreOperations.size());
        assertEquals(harness.gateway.restoreOperations.get(0), harness.gateway.restoreOperations.get(1));
        assertEquals(session.playerId(), harness.game.result().orElseThrow().player());
        assertEquals("INVALIDATED", harness.game.result().orElseThrow().reason());
        assertEquals(0, harness.world.ticketCount());
    }

    @Test
    void runningMatchRejectsLateJoinAndKeepsActiveTeleportCancellation() {
        Harness harness = new Harness(0, session -> true);
        PlayerSession session = harness.prepare(70);
        harness.startRunning(session.playerId());
        UUID latePlayer = uuid(80);
        UUID connection = uuid(81);
        UUID sessionId = uuid(82);
        UUID requestId = uuid(83);
        UUID snapshotId = uuid(84);
        harness.authentication.authenticated(new AuthenticatedSession(uuid(85), latePlayer,
                connection, NOW.minusSeconds(1), NOW.plusSeconds(600)));
        AdmissionRequest request = new AdmissionRequest(requestId, sessionId, snapshotId,
                harness.matchId, latePlayer, connection, GameKey.ELYTRA_RINGS, NOW);

        AdmissionResult rejected = harness.controller.join(player(latePlayer, harness.start),
                request, harness.token(request, latePlayer));

        assertEquals(AdmissionStatus.REJECTED, rejected.status());
        assertEquals("MATCH_UNAVAILABLE", rejected.code());
        assertTrue(harness.sessions.findByPlayer(latePlayer).isEmpty());

        PlayerTeleportEvent event = new PlayerTeleportEvent(player(session.playerId(), harness.start),
                harness.start, new Location(harness.world.world, 5, 65, 5), PlayerTeleportEvent.TeleportCause.PLUGIN);
        harness.controller.onTeleport(event);
        assertTrue(event.isCancelled());
    }

    private static UUID uuid(long value) { return new UUID(0L, value); }

    private static Player player(UUID id, Location location) {
        return proxy(Player.class, method -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "isOnline", "isValid", "teleport" -> true;
            case "getLocation" -> location.clone();
            default -> defaultValue(method.getReturnType());
        });
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not set controller test state", failure);
        }
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
        @Override public boolean save(PlayerSession session) { sessions.put(session.sessionId(), session); return true; }
        @Override public Optional<PlayerSession> find(UUID sessionId) { return Optional.ofNullable(sessions.get(sessionId)); }
        @Override public List<PlayerSession> nonTerminal() {
            return sessions.values().stream().filter(session -> !session.phase().terminal()).toList();
        }
    }

    private static final class RetryingGateway implements PlayerStateGateway {
        private int pendingRestores;
        private int restoreCalls;
        private final List<UUID> restoreOperations = new ArrayList<>();
        private final List<UUID> restoredPlayers = new ArrayList<>();
        private RetryingGateway(int pendingRestores) { this.pendingRestores = pendingRestores; }
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
            restoreOperations.add(operationId);
            if (pendingRestores > 0) { pendingRestores--; throw new WorldRecoveryPendingException(); }
            restoredPlayers.add(snapshot.playerId());
        }
    }

    private final class Harness {
        private final UUID matchId = UUID.randomUUID();
        private final UUID worldId = UUID.randomUUID();
        private final TicketWorld world = new TicketWorld(worldId);
        private final Location start = new Location(world.world, .5, 65, .5);
        private final SessionRegistry sessions = new SessionRegistry();
        private final MemorySessionRepository repository = new MemorySessionRepository();
        private final AuthenticationRegistry authentication = new AuthenticationRegistry();
        private final RegionAdmissionRegistry admissions = new RegionAdmissionRegistry();
        private final RetryingGateway gateway;
        private final SessionCoordinator coordinator;
        private final ElytraRingsGame game;
        private final ElytraChunkPreparation chunkPreparation;
        private final ElytraRingsController controller;
        private long gameOperation = 0;

        private Harness(int pendingRestores, Predicate<PlayerSession> restorationReady) {
            this(pendingRestores, restorationReady, false);
        }

        private Harness(int pendingRestores, Predicate<PlayerSession> restorationReady, boolean legacy) {
            Server server = proxy(Server.class, method -> method.getName().equals("isPrimaryThread")
                    ? true : defaultValue(method.getReturnType()));
            installBukkitServer(server);
            Plugin plugin = proxy(Plugin.class, method -> switch (method.getName()) {
                case "namespace" -> "ciaacplatform";
                case "getName" -> "test-plugin";
                case "getServer" -> server;
                default -> defaultValue(method.getReturnType());
            });
            gateway = new RetryingGateway(pendingRestores);
            coordinator = new SessionCoordinator(authentication, sessions, repository,
                    new InMemorySnapshotRepository(), gateway, IsolationPolicy.strictNoProgress(),
                    new SnapshotEnvelopeCodec(), Clock.fixed(NOW, ZoneOffset.UTC));
            var course = new ElytraCourseRevision("v1", worldId.toString(), List.of(
                    new RingCheckpoint(1, .5, 65, .5), new RingCheckpoint(2, 1.5, 65, .5)));
            var config = ElytraRingsConfig.dedicatedWorld(Duration.ofMinutes(1), course);
            var settings = new ElytraRingsPaperSettings(true, config, world.world, "elytra-boundary",
                    start, 3.0, 0, 0);
            ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
            regions.register(new ProtectedRegion("elytra-boundary", GameKey.ELYTRA_RINGS,
                    new CuboidRegion(worldId, -10, 0, -10, 10, 200, 10),
                    ProtectedRegionRole.PARTICIPANT_ONLY, true));
            TemporaryItemTagger tagger = new TemporaryItemTagger(plugin);
            chunkPreparation = new ElytraChunkPreparation(settings, plugin);
            game = new ElytraRingsGame(matchId, uuid(999), config);
            controller = legacy
                    ? new ElytraRingsController(matchId, game, settings, coordinator, sessions, admissions,
                            regions, tagger, Clock.fixed(NOW, ZoneOffset.UTC), StatisticsResultSink.unavailable(),
                            chunkPreparation)
                    : new ElytraRingsController(matchId, game, settings, coordinator, sessions, admissions,
                            regions, tagger, Clock.fixed(NOW, ZoneOffset.UTC), StatisticsResultSink.unavailable(),
                            chunkPreparation, restorationReady);
            chunkPreparation.tick();
            assertTrue(chunkPreparation.admissionReady());
        }

        private PlayerSession prepare(long seed) {
            UUID playerId = uuid(seed);
            UUID connectionId = uuid(seed + 1_000);
            UUID requestId = uuid(seed + 2_000);
            UUID sessionId = uuid(seed + 3_000);
            UUID snapshotId = uuid(seed + 4_000);
            authentication.authenticated(new AuthenticatedSession(uuid(seed + 5_000), playerId,
                    connectionId, NOW.minusSeconds(1), NOW.plusSeconds(600)));
            AdmissionRequest request = new AdmissionRequest(requestId, sessionId, snapshotId,
                    matchId, playerId, connectionId, GameKey.ELYTRA_RINGS, NOW);
            AdmissionResult prepared = coordinator.prepare(request);
            assertEquals(AdmissionStatus.PREPARED, prepared.status());
            assertTrue(coordinator.activate(sessionId, OperationIds.derive(requestId, "GAME_ACTIVE"), NOW));
            return prepared.session().orElseThrow();
        }

        private void openWaiting() {
            game.open(new com.ciaac.minecraft.minigames.hotpotato.OperationId(matchId, ++gameOperation));
            setField(controller, "operationSequence", gameOperation);
        }

        private void startRunning(UUID playerId) {
            game.open(new com.ciaac.minecraft.minigames.hotpotato.OperationId(matchId, ++gameOperation));
            game.start(NOW, new com.ciaac.minecraft.minigames.hotpotato.OperationId(matchId, ++gameOperation));
            setField(controller, "operationSequence", gameOperation);
        }

        private RegionAdmissionToken token(PlayerSession session) {
            return token(session.sessionId(), session.playerId());
        }

        private RegionAdmissionToken token(AdmissionRequest request, UUID playerId) {
            return token(request.sessionId(), playerId);
        }

        private RegionAdmissionToken token(UUID sessionId, UUID playerId) {
            return new RegionAdmissionToken(UUID.randomUUID(), sessionId, playerId,
                    "elytra-boundary", NOW, NOW.plusSeconds(60));
        }
    }

    private static final class TicketWorld {
        private final Set<String> tickets = new HashSet<>();
        private List<Player> players = List.of();
        private final World world;
        private TicketWorld(UUID id) {
            world = proxy(World.class, method -> switch (method.getName()) {
                case "getUID" -> id;
                case "getName" -> "elytra-test-world";
                case "isChunkLoaded" -> true;
                case "getPlayers" -> players;
                case "addPluginChunkTicket" -> { tickets.add("owned-ticket"); yield true; }
                case "removePluginChunkTicket" -> { tickets.remove("owned-ticket"); yield true; }
                default -> defaultValue(method.getReturnType());
            });
        }
        private int ticketCount() { return tickets.size(); }
    }

    @FunctionalInterface private interface Invocation {
        Object invoke(java.lang.reflect.Method method) throws Throwable;
    }
}
