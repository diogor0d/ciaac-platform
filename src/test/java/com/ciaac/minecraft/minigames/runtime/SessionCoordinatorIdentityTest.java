package com.ciaac.minecraft.minigames.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.SnapshotRecord;
import com.ciaac.minecraft.minigames.isolation.SnapshotState;
import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import com.ciaac.minecraft.minigames.persistence.SqliteSessionRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteSnapshotRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionCoordinatorIdentityTest {
    private static final Instant CAPTURED_AT = Instant.parse("2026-10-04T11:12:13.123456789Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void captureFailureReportsSourceAndCauseWithoutExceptionMessagesOrPlayerState() {
        List<String> diagnostics = new ArrayList<>();
        try (Fixture fixture = new Fixture(temporaryDirectory, "diagnostic.sqlite", snapshot -> {
            throw new IllegalStateException("private-provider-state", new IllegalArgumentException("private-credential"));
        }, diagnostics::add)) {
            AdmissionRequest request = fixture.request();
            assertEquals(AdmissionStatus.FAILED_CLOSED, fixture.coordinator.prepare(request).status());
            assertEquals(SessionPhase.CLOSED, fixture.sessionRepository.find(request.sessionId()).orElseThrow().phase());
            assertEquals(0, fixture.gateway.enterTemporaryCalls);
            assertEquals(1, diagnostics.size());
            String diagnostic = diagnostics.getFirst();
            assertTrue(diagnostic.contains("PREPARATION_FAILED"));
            assertTrue(diagnostic.contains(request.sessionId().toString()));
            assertTrue(diagnostic.contains("java.lang.IllegalStateException"));
            assertTrue(diagnostic.contains("java.lang.IllegalArgumentException"));
            assertTrue(diagnostic.contains("SessionCoordinatorIdentityTest"));
            assertFalse(diagnostic.contains("private-provider-state"));
            assertFalse(diagnostic.contains("private-credential"));
            assertFalse(diagnostic.contains(request.playerId().toString()));
        }
    }

    @Test
    void unavailableFailureReporterCannotPreventSafePreparationClosure() {
        try (Fixture fixture = new Fixture(temporaryDirectory, "reporter-failure.sqlite", snapshot -> {
            throw new IllegalStateException("fixture capture failure");
        }, diagnostic -> { throw new IllegalStateException("fixture logger failure"); })) {
            AdmissionRequest request = fixture.request();
            assertEquals(AdmissionStatus.FAILED_CLOSED, fixture.coordinator.prepare(request).status());
            assertEquals(SessionPhase.CLOSED, fixture.sessionRepository.find(request.sessionId()).orElseThrow().phase());
            assertTrue(fixture.snapshots.nonTerminal().isEmpty());
            assertEquals(0, fixture.gateway.enterTemporaryCalls);
        }
    }

    @Test
    void admissionPassesExactIdentityAndNanosecondCaptureTimeThroughSqliteReload() {
        Fixture fixture = new Fixture(temporaryDirectory, "identity.sqlite", UnaryOperator.identity());
        AdmissionRequest request = fixture.request();

        AdmissionResult result = fixture.coordinator.prepare(request);

        assertEquals(AdmissionStatus.PREPARED, result.status());
        assertEquals(1, fixture.gateway.captureCalls);
        assertEquals(1, fixture.gateway.enterTemporaryCalls);
        assertEquals(request.snapshotId(), fixture.gateway.captureCall.snapshotId());
        assertEquals(OperationIds.derive(request.requestId(), "SNAPSHOT_CAPTURE"),
                fixture.gateway.captureCall.operationId());
        assertEquals(request.sessionId(), fixture.gateway.captureCall.sessionId());
        assertEquals(request.matchId(), fixture.gateway.captureCall.matchId());
        assertEquals(request.playerId(), fixture.gateway.captureCall.playerId());
        assertEquals(request.connectionId(), fixture.gateway.captureCall.connectionId());
        assertEquals(request.game(), fixture.gateway.captureCall.game());
        assertEquals(CAPTURED_AT, fixture.gateway.captureCall.capturedAt());
        assertEquals(request.connectionId(), fixture.gateway.capturedSnapshot.capturedConnectionId());
        assertEquals(CAPTURED_AT, fixture.gateway.capturedSnapshot.capturedAt());

        fixture.close();
        try (SqliteDatabase reopened = new SqliteDatabase(temporaryDirectory, Path.of("identity.sqlite"))) {
            SnapshotRecord persisted = new SqliteSnapshotRepository(reopened, fixture.codec)
                    .find(request.snapshotId())
                    .orElseThrow();
            assertEquals(request.connectionId(), persisted.snapshot().capturedConnectionId());
            assertEquals(CAPTURED_AT, persisted.snapshot().capturedAt());
            assertEquals(123_456_789, persisted.snapshot().capturedAt().getNano());
            assertEquals(SnapshotState.TEMPORARY_APPLIED, persisted.state());
        }
    }

    @Test
    void recoveryOperationIdsAreScopedPerSessionAndStableForSameSessionRoot() {
        UUID sharedRoot = UUID.randomUUID();
        Fixture first = new Fixture(temporaryDirectory, "same-root-first.sqlite", UnaryOperator.identity());
        Fixture second = new Fixture(temporaryDirectory, "same-root-second.sqlite", UnaryOperator.identity());
        try (first; second) {
            AdmissionRequest firstRequest = first.request();
            AdmissionRequest secondRequest = second.request();
            assertEquals(AdmissionStatus.PREPARED, first.coordinator.prepare(firstRequest).status());
            assertEquals(AdmissionStatus.PREPARED, second.coordinator.prepare(secondRequest).status());

            assertEquals(AdmissionStatus.RECOVERED,
                    first.coordinator.recover(firstRequest.sessionId(), sharedRoot, "SAME_MATCH_END").status());
            assertEquals(AdmissionStatus.RECOVERED,
                    second.coordinator.recover(secondRequest.sessionId(), sharedRoot, "SAME_MATCH_END").status());

            UUID firstScopedRoot = OperationIds.derive(sharedRoot, "SESSION_" + firstRequest.sessionId());
            UUID secondScopedRoot = OperationIds.derive(sharedRoot, "SESSION_" + secondRequest.sessionId());
            assertEquals(OperationIds.derive(firstScopedRoot, "TEMPORARY_PURGE"), first.gateway.purgeOperationId);
            assertEquals(OperationIds.derive(secondScopedRoot, "TEMPORARY_PURGE"), second.gateway.purgeOperationId);
            assertEquals(OperationIds.derive(firstScopedRoot, "SNAPSHOT_RESTORE_APPLY"), first.gateway.restoreOperationId);
            assertEquals(OperationIds.derive(secondScopedRoot, "SNAPSHOT_RESTORE_APPLY"), second.gateway.restoreOperationId);
            assertFalse(first.gateway.purgeOperationId.equals(second.gateway.purgeOperationId));
            assertFalse(first.gateway.restoreOperationId.equals(second.gateway.restoreOperationId));
            assertEquals(first.gateway.purgeOperationId,
                    OperationIds.derive(OperationIds.derive(sharedRoot, "SESSION_" + firstRequest.sessionId()), "TEMPORARY_PURGE"));
        }
    }

    @Test
    void unsupportedGameIsRejectedBeforeSessionRegistrationOrCapture() {
        Fixture fixture = new Fixture(temporaryDirectory, "unsupported-game.sqlite", UnaryOperator.identity());
        fixture.gateway.unsupportedGames.add(GameKey.BUILD_BATTLE);
        AdmissionRequest request = fixture.request();

        AdmissionResult result = fixture.coordinator.prepare(request);

        assertEquals(AdmissionStatus.REJECTED, result.status());
        assertEquals("ISOLATION_UNAVAILABLE", result.code());
        assertEquals(0, fixture.gateway.captureCalls);
        assertTrue(fixture.sessions.findByPlayer(request.playerId()).isEmpty());
        assertTrue(fixture.sessionRepository.find(request.sessionId()).isEmpty());
        fixture.close();
    }

    @Test
    void rejectsCapturedSnapshotsWithForeignIdentityBeforePersistenceOrTemporaryMutation() {
        for (SnapshotMismatch mismatch : SnapshotMismatch.values()) {
            Fixture fixture = new Fixture(
                    temporaryDirectory,
                    "mismatch-" + mismatch.name().toLowerCase(java.util.Locale.ROOT) + ".sqlite",
                    snapshot -> mismatch.corrupt(snapshot));
            AdmissionRequest request = fixture.request();

            AdmissionResult result = fixture.coordinator.prepare(request);

            assertEquals(AdmissionStatus.FAILED_CLOSED, result.status(), mismatch.name());
            assertEquals(1, fixture.gateway.captureCalls, mismatch.name());
            assertEquals(0, fixture.gateway.enterTemporaryCalls, mismatch.name());
            assertTrue(fixture.snapshots.nonTerminal().isEmpty(), mismatch.name());
            assertEquals(SessionPhase.CLOSED,
                    fixture.sessionRepository.find(request.sessionId()).orElseThrow().phase(), mismatch.name());
            fixture.close();
        }
    }

    @Test
    void quarantinesSessionLinkedToValidSnapshotOwnedByAnotherPlayerBeforePurgeOrRestore() {
        Fixture fixture = new Fixture(temporaryDirectory, "foreign-linked.sqlite", UnaryOperator.identity());
        AdmissionRequest request = fixture.request();
        assertEquals(AdmissionStatus.PREPARED, fixture.coordinator.prepare(request).status());
        assertEquals(1, fixture.gateway.enterTemporaryCalls);

        PlayerStateSnapshot original = fixture.snapshots.find(request.snapshotId()).orElseThrow().snapshot();
        PlayerStateSnapshot foreignPlayerSnapshot = new PlayerStateSnapshot(
                original.schemaVersion(),
                original.snapshotId(),
                original.operationId(),
                original.sessionId(),
                original.matchId(),
                UUID.randomUUID(),
                original.capturedConnectionId(),
                original.game(),
                original.capturedAt(),
                original.facets());
        byte[] envelope = fixture.codec.encode(foreignPlayerSnapshot);
        String checksum = fixture.codec.checksum(envelope);
        fixture.database.transaction(connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE mg_snapshot
                    SET player_id = ?, envelope = ?, checksum_sha256 = ?
                    WHERE snapshot_id = ?
                    """)) {
                update.setString(1, foreignPlayerSnapshot.playerId().toString());
                update.setBytes(2, envelope);
                update.setString(3, checksum);
                update.setString(4, request.snapshotId().toString());
                assertEquals(1, update.executeUpdate());
            }
            return null;
        });
        SnapshotRecord validForeignRecord = fixture.snapshots.find(request.snapshotId()).orElseThrow();
        assertNotNull(validForeignRecord);
        assertEquals(foreignPlayerSnapshot.playerId(), validForeignRecord.snapshot().playerId());

        AdmissionResult recovery = fixture.coordinator.recover(
                request.sessionId(), UUID.randomUUID(), "FOREIGN_SNAPSHOT_FIXTURE");

        assertEquals(AdmissionStatus.QUARANTINED, recovery.status());
        assertEquals(0, fixture.gateway.purgeTemporaryCalls);
        assertEquals(0, fixture.gateway.restoreCalls);
        assertEquals(SnapshotState.QUARANTINED,
                fixture.snapshots.find(request.snapshotId()).orElseThrow().state());
        assertEquals(SessionPhase.QUARANTINED,
                fixture.sessionRepository.find(request.sessionId()).orElseThrow().phase());
        fixture.close();
    }

    private enum SnapshotMismatch {
        PLAYER {
            @Override PlayerStateSnapshot corrupt(PlayerStateSnapshot value) {
                return copy(value, value.snapshotId(), value.sessionId(), value.matchId(), UUID.randomUUID(),
                        value.capturedConnectionId(), value.game(), value.capturedAt());
            }
        },
        SESSION {
            @Override PlayerStateSnapshot corrupt(PlayerStateSnapshot value) {
                return copy(value, value.snapshotId(), UUID.randomUUID(), value.matchId(), value.playerId(),
                        value.capturedConnectionId(), value.game(), value.capturedAt());
            }
        },
        MATCH {
            @Override PlayerStateSnapshot corrupt(PlayerStateSnapshot value) {
                return copy(value, value.snapshotId(), value.sessionId(), UUID.randomUUID(), value.playerId(),
                        value.capturedConnectionId(), value.game(), value.capturedAt());
            }
        },
        GAME {
            @Override PlayerStateSnapshot corrupt(PlayerStateSnapshot value) {
                return copy(value, value.snapshotId(), value.sessionId(), value.matchId(), value.playerId(),
                        value.capturedConnectionId(), GameKey.ARENA, value.capturedAt());
            }
        },
        CONNECTION_EPOCH {
            @Override PlayerStateSnapshot corrupt(PlayerStateSnapshot value) {
                return copy(value, value.snapshotId(), value.sessionId(), value.matchId(), value.playerId(),
                        UUID.randomUUID(), value.game(), value.capturedAt());
            }
        },
        CAPTURE_OPERATION {
            @Override PlayerStateSnapshot corrupt(PlayerStateSnapshot value) {
                return new PlayerStateSnapshot(value.schemaVersion(), value.snapshotId(), UUID.randomUUID(),
                        value.sessionId(), value.matchId(), value.playerId(), value.capturedConnectionId(),
                        value.game(), value.capturedAt(), value.facets());
            }
        },
        CAPTURE_TIME {
            @Override PlayerStateSnapshot corrupt(PlayerStateSnapshot value) {
                return copy(value, value.snapshotId(), value.sessionId(), value.matchId(), value.playerId(),
                        value.capturedConnectionId(), value.game(), value.capturedAt().plusNanos(1));
            }
        };

        abstract PlayerStateSnapshot corrupt(PlayerStateSnapshot value);

        private static PlayerStateSnapshot copy(
                PlayerStateSnapshot value,
                UUID snapshotId,
                UUID sessionId,
                UUID matchId,
                UUID playerId,
                UUID connectionId,
                GameKey game,
                Instant capturedAt) {
            return new PlayerStateSnapshot(value.schemaVersion(), snapshotId, value.operationId(), sessionId,
                    matchId, playerId, connectionId, game, capturedAt, value.facets());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final SqliteDatabase database;
        private final SnapshotEnvelopeCodec codec = new SnapshotEnvelopeCodec();
        private final SqliteSessionRepository sessionRepository;
        private final SqliteSnapshotRepository snapshots;
        private final SessionRegistry sessions = new SessionRegistry();
        private final AuthenticationRegistry authentication = new AuthenticationRegistry();
        private final RecordingGateway gateway;
        private final SessionCoordinator coordinator;
        private final UUID requestId = UUID.randomUUID();
        private final UUID sessionId = UUID.randomUUID();
        private final UUID snapshotId = UUID.randomUUID();
        private final UUID matchId = UUID.randomUUID();
        private final UUID playerId = UUID.randomUUID();
        private final UUID connectionId = UUID.randomUUID();

        private Fixture(Path directory, String databaseName, UnaryOperator<PlayerStateSnapshot> snapshotTransform) {
            this(directory, databaseName, snapshotTransform, ignored -> {});
        }

        private Fixture(Path directory, String databaseName, UnaryOperator<PlayerStateSnapshot> snapshotTransform,
                Consumer<String> failureReporter) {
            database = new SqliteDatabase(directory, Path.of(databaseName));
            sessionRepository = new SqliteSessionRepository(database);
            snapshots = new SqliteSnapshotRepository(database, codec);
            gateway = new RecordingGateway(snapshotTransform);
            authentication.authenticated(new AuthenticatedSession(
                    UUID.randomUUID(), playerId, connectionId,
                    CAPTURED_AT.minusSeconds(1), CAPTURED_AT.plusSeconds(60)));
            coordinator = new SessionCoordinator(
                    authentication,
                    sessions,
                    sessionRepository,
                    snapshots,
                    gateway,
                    IsolationPolicy.strictNoProgress(),
                    codec,
                    Clock.fixed(CAPTURED_AT, ZoneOffset.UTC), failureReporter);
        }

        private AdmissionRequest request() {
            return new AdmissionRequest(requestId, sessionId, snapshotId, matchId, playerId, connectionId,
                    GameKey.BUILD_BATTLE, CAPTURED_AT);
        }

        @Override public void close() { database.close(); }
    }

    private static final class RecordingGateway implements PlayerStateGateway {
        private final UnaryOperator<PlayerStateSnapshot> snapshotTransform;
        private final Set<GameKey> unsupportedGames = EnumSet.noneOf(GameKey.class);
        private int captureCalls;
        private int enterTemporaryCalls;
        private int purgeTemporaryCalls;
        private int restoreCalls;
        private UUID purgeOperationId;
        private UUID restoreOperationId;
        private CaptureCall captureCall;
        private PlayerStateSnapshot capturedSnapshot;

        private RecordingGateway(UnaryOperator<PlayerStateSnapshot> snapshotTransform) {
            this.snapshotTransform = snapshotTransform;
        }

        @Override public java.util.Set<PlayerStateFacet> supportedFacets() {
            return EnumSet.allOf(PlayerStateFacet.class);
        }

        @Override public java.util.Set<PlayerStateFacet> supportedFacets(GameKey game) {
            return unsupportedGames.contains(game) ? Set.of() : supportedFacets();
        }

        @Override
        public PlayerStateSnapshot capture(
                UUID snapshotId,
                UUID operationId,
                UUID sessionId,
                UUID matchId,
                UUID playerId,
                UUID connectionId,
                GameKey game,
                Instant capturedAt) {
            captureCalls++;
            captureCall = new CaptureCall(snapshotId, operationId, sessionId, matchId, playerId,
                    connectionId, game, capturedAt);
            EnumMap<PlayerStateFacet, byte[]> facets = new EnumMap<>(PlayerStateFacet.class);
            for (PlayerStateFacet facet : PlayerStateFacet.values()) facets.put(facet, new byte[] {(byte) facet.ordinal()});
            capturedSnapshot = snapshotTransform.apply(new PlayerStateSnapshot(
                    2, snapshotId, operationId, sessionId, matchId, playerId, connectionId,
                    game, capturedAt, facets));
            return capturedSnapshot;
        }

        @Override public void enterTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {
            enterTemporaryCalls++;
        }

        @Override public void purgeTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {
            purgeTemporaryCalls++;
            purgeOperationId = operationId;
        }

        @Override public void restore(UUID operationId, PlayerStateSnapshot snapshot) {
            restoreCalls++;
            restoreOperationId = operationId;
        }
    }

    private record CaptureCall(
            UUID snapshotId,
            UUID operationId,
            UUID sessionId,
            UUID matchId,
            UUID playerId,
            UUID connectionId,
            GameKey game,
            Instant capturedAt) {}
}
