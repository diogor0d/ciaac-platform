package com.ciaac.minecraft.minigames.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.SnapshotState;
import com.ciaac.minecraft.minigames.isolation.WorldRecoveryPendingException;
import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import com.ciaac.minecraft.minigames.persistence.SqliteSessionRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteSnapshotRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionCoordinatorWorldRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void pendingWorldCleanupKeepsSessionProtectedAndRetryClosesAfterFullRestore() {
        try (Fixture fixture = new Fixture("partial-world-cleanup.sqlite")) {
            AdmissionRequest request = fixture.request();
            assertEquals(AdmissionStatus.PREPARED, fixture.coordinator.prepare(request).status());
            fixture.gateway.pendingPurges = 1;

            AdmissionResult firstAttempt = fixture.coordinator.recover(
                    request.sessionId(), UUID.randomUUID(), "WORLD_CLEANUP");

            assertEquals(AdmissionStatus.REJECTED, firstAttempt.status());
            assertEquals("RECOVERY_PENDING", firstAttempt.code());
            assertEquals(SessionPhase.RECOVERING,
                    fixture.sessionRepository.find(request.sessionId()).orElseThrow().phase());
            assertEquals(SnapshotState.RESTORING,
                    fixture.snapshots.find(request.snapshotId()).orElseThrow().state());
            assertTrue(fixture.sessions.findByPlayer(request.playerId()).isPresent());
            assertFalse(fixture.sessionRepository.find(request.sessionId()).orElseThrow().phase().terminal());
            assertFalse(fixture.snapshots.find(request.snapshotId()).orElseThrow().state().terminal());

            AdmissionRequest duplicateAdmission = fixture.request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            AdmissionResult blockedAdmission = fixture.coordinator.prepare(duplicateAdmission);
            assertEquals(AdmissionStatus.REJECTED, blockedAdmission.status());
            assertEquals("SESSION_ALREADY_ACTIVE", blockedAdmission.code());
            assertEquals(1, fixture.gateway.captureCalls);

            AdmissionResult retry = fixture.coordinator.recover(
                    request.sessionId(), UUID.randomUUID(), "WORLD_CLEANUP_RETRY");

            assertEquals(AdmissionStatus.RECOVERED, retry.status());
            assertEquals(SessionPhase.CLOSED,
                    fixture.sessionRepository.find(request.sessionId()).orElseThrow().phase());
            assertEquals(SnapshotState.RESTORED,
                    fixture.snapshots.find(request.snapshotId()).orElseThrow().state());
            assertTrue(fixture.sessions.findByPlayer(request.playerId()).isEmpty());
            assertEquals(2, fixture.gateway.purgeCalls);
            assertEquals(1, fixture.gateway.restoreCalls);
        }
    }

    @Test
    void ordinaryRuntimeFailureDuringRestoreStillQuarantinesSession() {
        try (Fixture fixture = new Fixture("runtime-restore-failure.sqlite")) {
            AdmissionRequest request = fixture.request();
            assertEquals(AdmissionStatus.PREPARED, fixture.coordinator.prepare(request).status());
            fixture.gateway.failPurge = true;

            AdmissionResult result = fixture.coordinator.recover(
                    request.sessionId(), UUID.randomUUID(), "WORLD_CLEANUP");

            assertEquals(AdmissionStatus.QUARANTINED, result.status());
            assertEquals(SessionPhase.QUARANTINED,
                    fixture.sessionRepository.find(request.sessionId()).orElseThrow().phase());
            assertEquals(SnapshotState.QUARANTINED,
                    fixture.snapshots.find(request.snapshotId()).orElseThrow().state());
            assertTrue(fixture.sessions.findByPlayer(request.playerId()).isPresent());
            assertEquals(0, fixture.gateway.restoreCalls);
        }
    }

    @Test
    void restoredSnapshotWithRestoringSessionClosesWithoutRepeatingWorldMutation() {
        try (Fixture fixture = new Fixture("restored-snapshot-crash-state.sqlite")) {
            AdmissionRequest request = fixture.request();
            assertEquals(AdmissionStatus.PREPARED, fixture.coordinator.prepare(request).status());
            PlayerSession session = fixture.coordinator.findSession(request.sessionId()).orElseThrow();
            assertTrue(fixture.coordinator.activate(request.sessionId(), UUID.randomUUID(), NOW));
            session.transition(UUID.randomUUID(), SessionPhase.ACTIVE, SessionPhase.FINISHING,
                    NOW, "CRASH_FIXTURE");
            fixture.sessionRepository.save(session);
            session.transition(UUID.randomUUID(), SessionPhase.FINISHING, SessionPhase.RESTORING,
                    NOW, "CRASH_FIXTURE");
            fixture.sessionRepository.save(session);
            fixture.markSnapshotRestored(request.snapshotId());

            AdmissionResult result = fixture.coordinator.recover(
                    request.sessionId(), UUID.randomUUID(), "RESTART_RECOVERY");

            assertEquals(AdmissionStatus.RECOVERED, result.status());
            assertEquals(SessionPhase.CLOSED,
                    fixture.sessionRepository.find(request.sessionId()).orElseThrow().phase());
            assertEquals(SnapshotState.RESTORED,
                    fixture.snapshots.find(request.snapshotId()).orElseThrow().state());
            assertEquals(0, fixture.gateway.purgeCalls);
            assertEquals(0, fixture.gateway.restoreCalls);
        }
    }

    @Test
    void restoredSnapshotWithRecoveringSessionClosesAfterCrashBeforeSessionRestoreCommit() {
        try (Fixture fixture = new Fixture("restored-snapshot-recovering-crash-state.sqlite")) {
            AdmissionRequest request = fixture.request();
            assertEquals(AdmissionStatus.PREPARED, fixture.coordinator.prepare(request).status());
            assertTrue(fixture.coordinator.activate(request.sessionId(), UUID.randomUUID(), NOW));
            PlayerSession session = fixture.coordinator.findSession(request.sessionId()).orElseThrow();
            session.transition(UUID.randomUUID(), SessionPhase.ACTIVE, SessionPhase.RECOVERING,
                    NOW, "CRASH_FIXTURE");
            fixture.sessionRepository.save(session);
            fixture.markSnapshotRestored(request.snapshotId());

            AdmissionResult result = fixture.coordinator.recover(
                    request.sessionId(), UUID.randomUUID(), "RESTART_RECOVERY");

            assertEquals(AdmissionStatus.RECOVERED, result.status());
            assertEquals(SessionPhase.CLOSED,
                    fixture.sessionRepository.find(request.sessionId()).orElseThrow().phase());
            assertEquals(SnapshotState.RESTORED,
                    fixture.snapshots.find(request.snapshotId()).orElseThrow().state());
            assertEquals(0, fixture.gateway.purgeCalls);
            assertEquals(0, fixture.gateway.restoreCalls);
        }
    }

    @Test
    void restoredSnapshotCannotBeAcceptedForAnActiveSession() {
        try (Fixture fixture = new Fixture("active-session-restored-snapshot.sqlite")) {
            AdmissionRequest request = fixture.request();
            assertEquals(AdmissionStatus.PREPARED, fixture.coordinator.prepare(request).status());
            assertTrue(fixture.coordinator.activate(request.sessionId(), UUID.randomUUID(), NOW));
            fixture.markSnapshotRestored(request.snapshotId());

            AdmissionResult result = fixture.coordinator.recover(
                    request.sessionId(), UUID.randomUUID(), "INCONSISTENT_CRASH_STATE");

            assertEquals(AdmissionStatus.QUARANTINED, result.status());
            assertEquals(SessionPhase.QUARANTINED,
                    fixture.sessionRepository.find(request.sessionId()).orElseThrow().phase());
            assertEquals(SnapshotState.RESTORED,
                    fixture.snapshots.find(request.snapshotId()).orElseThrow().state());
            assertEquals(0, fixture.gateway.purgeCalls);
            assertEquals(0, fixture.gateway.restoreCalls);
        }
    }

    private final class Fixture implements AutoCloseable {
        private final SqliteDatabase database;
        private final SnapshotEnvelopeCodec codec = new SnapshotEnvelopeCodec();
        private final SqliteSessionRepository sessionRepository;
        private final SqliteSnapshotRepository snapshots;
        private final SessionRegistry sessions = new SessionRegistry();
        private final AuthenticationRegistry authentication = new AuthenticationRegistry();
        private final Gateway gateway = new Gateway();
        private final SessionCoordinator coordinator;
        private final UUID playerId = UUID.randomUUID();
        private final UUID connectionId = UUID.randomUUID();
        private final UUID sessionId = UUID.randomUUID();
        private final UUID snapshotId = UUID.randomUUID();
        private final UUID matchId = UUID.randomUUID();

        private Fixture(String databaseName) {
            database = new SqliteDatabase(temporaryDirectory, Path.of(databaseName));
            sessionRepository = new SqliteSessionRepository(database);
            snapshots = new SqliteSnapshotRepository(database, codec);
            authentication.authenticated(new AuthenticatedSession(
                    UUID.randomUUID(), playerId, connectionId, NOW.minusSeconds(1), NOW.plusSeconds(60)));
            coordinator = new SessionCoordinator(authentication, sessions, sessionRepository, snapshots,
                    gateway, IsolationPolicy.strictNoProgress(), codec, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private AdmissionRequest request() {
            return request(UUID.randomUUID(), sessionId, snapshotId);
        }

        private AdmissionRequest request(UUID requestId, UUID targetSessionId, UUID targetSnapshotId) {
            return new AdmissionRequest(requestId, targetSessionId, targetSnapshotId, matchId,
                    playerId, connectionId, GameKey.BUILD_BATTLE, NOW);
        }

        private void markSnapshotRestored(UUID id) {
            snapshots.transition(id, UUID.randomUUID(), SnapshotState.TEMPORARY_APPLIED,
                    SnapshotState.RESTORING, NOW, "CRASH_FIXTURE");
            snapshots.transition(id, UUID.randomUUID(), SnapshotState.RESTORING,
                    SnapshotState.RESTORED, NOW, "CRASH_FIXTURE");
        }

        @Override public void close() { database.close(); }
    }

    private static final class Gateway implements PlayerStateGateway {
        private int captureCalls;
        private int purgeCalls;
        private int restoreCalls;
        private int pendingPurges;
        private boolean failPurge;

        @Override public Set<PlayerStateFacet> supportedFacets() {
            return EnumSet.allOf(PlayerStateFacet.class);
        }

        @Override
        public PlayerStateSnapshot capture(UUID snapshotId, UUID operationId, UUID sessionId,
                UUID matchId, UUID playerId, UUID connectionId, GameKey game, Instant capturedAt) {
            captureCalls++;
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
            if (failPurge) throw new IllegalStateException("world cleanup failed");
            if (pendingPurges > 0) {
                pendingPurges--;
                throw new WorldRecoveryPendingException();
            }
        }

        @Override public void restore(UUID operationId, PlayerStateSnapshot snapshot) { restoreCalls++; }
    }
}
