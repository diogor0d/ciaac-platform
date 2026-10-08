package com.ciaac.minecraft.minigames.paper.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.SnapshotState;
import com.ciaac.minecraft.minigames.isolation.WorldRecoveryPendingException;
import com.ciaac.minecraft.minigames.persistence.AuditEvent;
import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import com.ciaac.minecraft.minigames.persistence.SqliteSessionRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteSnapshotRepository;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.AuthenticatedSession;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionRecoveryPendingRetryTest {
    private static final Instant NOW = Instant.parse("2026-10-07T13:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void pendingColorFloorRestoreRetriesToCompletionAndPostCloseHeartbeatIsInert() {
        try (Fixture fixture = new Fixture("pending-color-floor.sqlite", GameKey.COLOR_FLOOR)) {
            assertEquals(AdmissionStatus.PREPARED, fixture.coordinator.prepare(fixture.request()).status());
            fixture.gateway.pendingPurges = 1;

            var first = fixture.service.onAuthenticated(fixture.player);

            assertEquals(AdmissionStatus.REJECTED, first.status());
            assertEquals("RECOVERY_PENDING", first.code());
            assertEquals(SessionPhase.RECOVERING, fixture.session().phase());
            assertEquals(SnapshotState.RESTORING, fixture.snapshotState());
            assertEquals(1, fixture.gateway.purgeCalls);
            assertEquals(0, fixture.gateway.restoreCalls);

            fixture.service.retryPendingWorldRecovery(fixture.player);

            assertEquals(SessionPhase.CLOSED, fixture.session().phase());
            assertEquals(SnapshotState.RESTORED, fixture.snapshotState());
            assertTrue(fixture.sessions.findById(fixture.sessionId).isEmpty());
            assertEquals(2, fixture.gateway.purgeCalls);
            assertEquals(1, fixture.gateway.restoreCalls);

            fixture.service.retryPendingWorldRecovery(fixture.player);

            assertEquals(2, fixture.gateway.purgeCalls);
            assertEquals(1, fixture.gateway.restoreCalls);
        }
    }

    @Test
    void heartbeatDoesNotStopAnActiveOrdinaryGame() {
        try (Fixture fixture = new Fixture("active-arena-heartbeat.sqlite", GameKey.ARENA)) {
            assertEquals(AdmissionStatus.PREPARED, fixture.coordinator.prepare(fixture.request()).status());
            assertTrue(fixture.coordinator.activate(fixture.sessionId, UUID.randomUUID(), NOW));

            fixture.service.retryPendingWorldRecovery(fixture.player);

            assertEquals(SessionPhase.ACTIVE, fixture.session().phase());
            assertEquals(0, fixture.stopCalls.get());
            assertEquals(0, fixture.gateway.purgeCalls);
            assertEquals(0, fixture.gateway.restoreCalls);
        }
    }

    @Test
    void retryRequiresColorFloorAndRecoveringOrRestoringPhase() {
        try (Fixture colorFloorActive = new Fixture("color-floor-active.sqlite", GameKey.COLOR_FLOOR);
                Fixture arenaRecovering = new Fixture("arena-recovering.sqlite", GameKey.ARENA);
                Fixture arenaRestoring = new Fixture("arena-restoring.sqlite", GameKey.ARENA)) {
            for (Fixture fixture : List.of(colorFloorActive, arenaRecovering, arenaRestoring)) {
                assertEquals(AdmissionStatus.PREPARED, fixture.coordinator.prepare(fixture.request()).status());
                assertTrue(fixture.coordinator.activate(fixture.sessionId, UUID.randomUUID(), NOW));
            }
            arenaRecovering.transitionSession(SessionPhase.RECOVERING);
            arenaRestoring.transitionSession(SessionPhase.FINISHING);
            arenaRestoring.transitionSession(SessionPhase.RESTORING);

            for (Fixture fixture : List.of(colorFloorActive, arenaRecovering, arenaRestoring)) {
                SessionPhase before = fixture.session().phase();
                fixture.service.retryPendingWorldRecovery(fixture.player);
                assertEquals(before, fixture.session().phase());
                assertEquals(0, fixture.gateway.purgeCalls);
                assertEquals(0, fixture.gateway.restoreCalls);
                assertEquals(0, fixture.stopCalls.get());
            }
        }
    }

    @Test
    void restoringColorFloorSessionCanFinishAfterRestartWithoutRepeatingRestore() {
        try (Fixture fixture = new Fixture("color-floor-restoring.sqlite", GameKey.COLOR_FLOOR)) {
            assertEquals(AdmissionStatus.PREPARED, fixture.coordinator.prepare(fixture.request()).status());
            assertTrue(fixture.coordinator.activate(fixture.sessionId, UUID.randomUUID(), NOW));
            fixture.transitionSession(SessionPhase.FINISHING);
            fixture.transitionSession(SessionPhase.RESTORING);
            fixture.transitionSnapshot(SnapshotState.RESTORING);
            fixture.transitionSnapshot(SnapshotState.RESTORED);

            fixture.service.retryPendingWorldRecovery(fixture.player);

            assertEquals(SessionPhase.CLOSED, fixture.session().phase());
            assertEquals(0, fixture.gateway.purgeCalls);
            assertEquals(0, fixture.gateway.restoreCalls);
        }
    }

    @Test
    void unauthenticatedStaleAndOfflineConnectionsCannotRetryRecovery() {
        try (Fixture unauthenticated = new Fixture("retry-unauthenticated.sqlite", GameKey.COLOR_FLOOR);
                Fixture stale = new Fixture("retry-stale.sqlite", GameKey.COLOR_FLOOR);
                Fixture offline = new Fixture("retry-offline.sqlite", GameKey.COLOR_FLOOR)) {
            List<Fixture> fixtures = List.of(unauthenticated, stale, offline);
            for (Fixture fixture : fixtures) {
                assertEquals(AdmissionStatus.PREPARED, fixture.coordinator.prepare(fixture.request()).status());
                fixture.transitionSession(SessionPhase.RECOVERING);
            }
            unauthenticated.authentication.invalidatePlayer(unauthenticated.playerId);
            stale.replaceConnectionAfterAuthentication();
            offline.online.set(false);

            for (Fixture fixture : fixtures) {
                fixture.service.retryPendingWorldRecovery(fixture.player);
                assertEquals(SessionPhase.RECOVERING, fixture.session().phase());
                assertEquals(SnapshotState.TEMPORARY_APPLIED, fixture.snapshotState());
                assertEquals(0, fixture.gateway.purgeCalls);
                assertEquals(0, fixture.gateway.restoreCalls);
                assertEquals(0, fixture.stopCalls.get());
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        private final SqliteDatabase database;
        private final SnapshotEnvelopeCodec codec = new SnapshotEnvelopeCodec();
        private final SqliteSessionRepository sessionRepository;
        private final SqliteSnapshotRepository snapshots;
        private final SessionRegistry sessions = new SessionRegistry();
        private final AuthenticationRegistry authentication = new AuthenticationRegistry();
        private final ConnectionRegistry connections = new ConnectionRegistry();
        private final Gateway gateway = new Gateway();
        private final AtomicBoolean online = new AtomicBoolean(true);
        private final AtomicInteger stopCalls = new AtomicInteger();
        private final List<AuditEvent> audits = new ArrayList<>();
        private final Player player;
        private final UUID playerId = UUID.randomUUID();
        private final UUID sessionId = UUID.randomUUID();
        private final UUID snapshotId = UUID.randomUUID();
        private final UUID matchId = UUID.randomUUID();
        private final GameKey game;
        private final SessionCoordinator coordinator;
        private final SessionRecoveryService service;

        private Fixture(String databaseName, GameKey game) {
            this.game = game;
            database = new SqliteDatabase(temporaryDirectory, Path.of(databaseName));
            sessionRepository = new SqliteSessionRepository(database);
            snapshots = new SqliteSnapshotRepository(database, codec);
            player = player(playerId, online);
            ConnectionRegistry.Connection connection = connections.begin(player, NOW.minusSeconds(10));
            authentication.authenticated(new AuthenticatedSession(UUID.randomUUID(), playerId,
                    connection.id(), NOW.minusSeconds(1), NOW.plusSeconds(3600)));
            coordinator = new SessionCoordinator(authentication, sessions, sessionRepository, snapshots,
                    gateway, IsolationPolicy.strictNoProgress(), codec, Clock.fixed(NOW, ZoneOffset.UTC));
            service = new SessionRecoveryService(sessionRepository, sessions, coordinator, audits::add,
                    authentication, connections, Clock.fixed(NOW, ZoneOffset.UTC), ignored -> stopCalls.incrementAndGet());
        }

        private com.ciaac.minecraft.minigames.runtime.AdmissionRequest request() {
            return new com.ciaac.minecraft.minigames.runtime.AdmissionRequest(UUID.randomUUID(), sessionId,
                    snapshotId, matchId, playerId, connections.current(playerId).orElseThrow().id(),
                    game, NOW);
        }

        private PlayerSession session() {
            return sessions.findById(sessionId).orElseGet(() -> sessionRepository.find(sessionId).orElseThrow());
        }

        private SnapshotState snapshotState() {
            return snapshots.find(snapshotId).orElseThrow().state();
        }

        private void transitionSession(SessionPhase target) {
            PlayerSession session = session();
            SessionPhase from = session.phase();
            session.transition(UUID.randomUUID(), from, target, NOW, "RETRY_FIXTURE");
            sessionRepository.save(session);
        }

        private void transitionSnapshot(SnapshotState target) {
            SnapshotState from = snapshotState();
            snapshots.transition(snapshotId, UUID.randomUUID(), from, target, NOW, "RETRY_FIXTURE");
        }

        private void replaceConnectionAfterAuthentication() {
            connections.begin(player, NOW);
        }

        @Override public void close() { database.close(); }
    }

    private static final class Gateway implements PlayerStateGateway {
        private int purgeCalls;
        private int restoreCalls;
        private int pendingPurges;

        @Override public Set<PlayerStateFacet> supportedFacets() {
            return EnumSet.allOf(PlayerStateFacet.class);
        }

        @Override
        public PlayerStateSnapshot capture(UUID snapshotId, UUID operationId, UUID sessionId,
                UUID matchId, UUID playerId, UUID connectionId, GameKey game, Instant capturedAt) {
            EnumMap<PlayerStateFacet, byte[]> facets = new EnumMap<>(PlayerStateFacet.class);
            for (PlayerStateFacet facet : PlayerStateFacet.values()) {
                facets.put(facet, new byte[] {(byte) facet.ordinal()});
            }
            return new PlayerStateSnapshot(2, snapshotId, operationId, sessionId, matchId,
                    playerId, connectionId, game, capturedAt, facets);
        }

        @Override public void enterTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {}

        @Override public void purgeTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {
            purgeCalls++;
            if (pendingPurges > 0) {
                pendingPurges--;
                throw new WorldRecoveryPendingException();
            }
        }

        @Override public void restore(UUID operationId, PlayerStateSnapshot snapshot) { restoreCalls++; }
    }

    private static Player player(UUID playerId, AtomicBoolean online) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "isOnline" -> online.get();
                    case "isValid" -> true;
                    case "sendMessage" -> null;
                    case "toString" -> "Retry test player";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
