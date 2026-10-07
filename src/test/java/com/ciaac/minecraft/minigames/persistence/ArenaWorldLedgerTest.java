package com.ciaac.minecraft.minigames.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArenaWorldLedgerTest {
    @TempDir Path temporary;
    private static final byte[] MANIFEST = {10, 20, 30};

    @BeforeEach void canonicalTemporaryDirectory() throws Exception { temporary = temporary.toRealPath(); }

    @Test void captureIsEqualOnlyIdempotentAcrossRecoveryPhaseAndSurvivesReopen() {
        PlayerStateOperation capture = operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7);
        Path directory = temporary.resolve("arena");
        byte[] callerBytes = MANIFEST.clone();
        try (var ledger = new ArenaWorldLedger(directory)) {
            var lease = ledger.capture(capture, callerBytes);
            assertEquals(ArenaWorldLedger.Status.CAPTURED, lease.status());
            callerBytes[0] = 99;
            byte[] returnedManifest = lease.manifest(); returnedManifest[1] = 99;
            var same = ledger.capture(operation(PlayerStateOperation.Kind.PURGE, 90, 7, 44), MANIFEST);
            assertArrayEquals(MANIFEST, same.manifest());
            assertEquals(ArenaWorldLedger.Status.CAPTURED, same.status());
            assertThrows(IllegalStateException.class, () -> ledger.capture(capture, new byte[] {1}));
            assertThrows(IllegalStateException.class, () -> ledger.requireLease(operation(PlayerStateOperation.Kind.PURGE, 90, 70, 44)));
        }
        try (var ledger = new ArenaWorldLedger(directory)) {
            var restoredContext = operation(PlayerStateOperation.Kind.RESTORE, 91, 7, 55);
            assertArrayEquals(MANIFEST, ledger.requireLease(restoredContext).manifest());
            assertArrayEquals(MANIFEST, ledger.requireLease(restoredContext.sessionId()).manifest());
        }
    }

    @Test void aLaterPhaseMayReplayAnExistingCaptureButCannotCreateOne() {
        PlayerStateOperation laterPhase = operation(PlayerStateOperation.Kind.PURGE, 90, 7, 44);
        Path directory = temporary.resolve("capture-phase");
        try (var ledger = new ArenaWorldLedger(directory)) {
            assertThrows(IllegalStateException.class, () -> ledger.capture(laterPhase, MANIFEST));
            assertThrows(IllegalStateException.class, () -> ledger.requireLease(laterPhase));

            PlayerStateOperation capture = operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7);
            ledger.capture(capture, MANIFEST);
            assertEquals(ArenaWorldLedger.Status.CAPTURED, ledger.capture(laterPhase, MANIFEST).status());
        }
    }

    @Test void bindsEveryFrozenCaptureIdentityFieldButExcludesPhaseAndRecoveryConnection() {
        PlayerStateOperation capture = operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7);
        Path directory = temporary.resolve("identity");
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(capture, MANIFEST);
            List<PlayerStateOperation> conflicts = List.of(
                    operationWith(capture, uuid(22), capture.snapshotId(), capture.sessionId(), capture.matchId(), capture.playerId(), uuid(7), capture.game(), capture.capturedAt()),
                    operationWith(capture, capture.captureOperationId(), uuid(23), capture.sessionId(), capture.matchId(), capture.playerId(), uuid(7), capture.game(), capture.capturedAt()),
                    operationWith(capture, capture.captureOperationId(), capture.snapshotId(), uuid(24), capture.matchId(), capture.playerId(), uuid(7), capture.game(), capture.capturedAt()),
                    operationWith(capture, capture.captureOperationId(), capture.snapshotId(), capture.sessionId(), uuid(25), capture.playerId(), uuid(7), capture.game(), capture.capturedAt()),
                    operationWith(capture, capture.captureOperationId(), capture.snapshotId(), capture.sessionId(), capture.matchId(), uuid(26), uuid(7), capture.game(), capture.capturedAt()),
                    operationWith(capture, capture.captureOperationId(), capture.snapshotId(), capture.sessionId(), capture.matchId(), capture.playerId(), uuid(99), capture.game(), capture.capturedAt()),
                    operationWith(capture, capture.captureOperationId(), capture.snapshotId(), capture.sessionId(), capture.matchId(), capture.playerId(), uuid(7), capture.game(), capture.capturedAt().plusNanos(1)));
            for (PlayerStateOperation conflict : conflicts)
                assertThrows(IllegalStateException.class, () -> ledger.requireLease(conflict));
            assertThrows(IllegalArgumentException.class, () -> ledger.requireLease(operationWith(capture,
                    capture.captureOperationId(), capture.snapshotId(), capture.sessionId(), capture.matchId(),
                    capture.playerId(), uuid(7), GameKey.BUILD_BATTLE, capture.capturedAt())));
            assertThrows(IllegalArgumentException.class, () -> ledger.capture(capture, new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> ledger.capture(capture, new byte[128 * 1024 + 1]));
        }
    }

    @Test void enforcesLeaseAndEntityPhasesAndAllowsOnlyRemovalBeforePurgeCompletion() {
        PlayerStateOperation capture = operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7);
        try (var ledger = new ArenaWorldLedger(temporary.resolve("phases"))) {
            ledger.capture(capture, MANIFEST);
            assertThrows(IllegalStateException.class, () -> ledger.beginEntity(capture, uuid(50), uuid(60), ArenaWorldLedger.EntityType.ARROW));
            assertEquals(ArenaWorldLedger.Status.ARMED, ledger.arm(capture).status());
            assertEquals(ArenaWorldLedger.Status.ARMED, ledger.arm(operation(PlayerStateOperation.Kind.RESTORE, 70, 7, 80)).status());
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    ledger.beginEntity(capture, uuid(50), uuid(60), ArenaWorldLedger.EntityType.ARROW).status());
            assertEquals(ArenaWorldLedger.Status.PURGING,
                    ledger.beginPurge(operation(PlayerStateOperation.Kind.PURGE, 71, 7, 81)).status());
            assertEquals(ArenaWorldLedger.Status.PURGING, ledger.beginPurge(capture).status());
            assertThrows(IllegalStateException.class, () -> ledger.completePurge(capture));
            assertEquals(ArenaWorldLedger.EntityStatus.CONFIRMED, ledger.confirmEntity(capture, uuid(50)).status());
            assertEquals(ArenaWorldLedger.EntityStatus.CONFIRMED, ledger.confirmEntity(capture, uuid(50)).status());
            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED, ledger.markRemoved(capture, uuid(50)).status());
            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED, ledger.markRemoved(capture, uuid(50)).status());
            assertThrows(IllegalStateException.class, () -> ledger.confirmEntity(capture, uuid(50)));
            assertEquals(ArenaWorldLedger.Status.PURGED, ledger.completePurge(capture).status());
            assertEquals(ArenaWorldLedger.Status.RESTORED, ledger.markRestored(capture).status());
            assertEquals(ArenaWorldLedger.Status.RESTORED, ledger.markRestored(capture).status());
            assertEquals(ArenaWorldLedger.Status.RESTORED, ledger.beginPurge(capture).status());
            assertEquals(ArenaWorldLedger.Status.RESTORED, ledger.completePurge(capture).status());
        }
    }

    @Test void pendingResourcesSurviveRestartAndEntityListIsImmutableAndSorted() {
        PlayerStateOperation capture = operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7);
        Path directory = temporary.resolve("entities");
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(capture, MANIFEST); ledger.arm(capture);
            ledger.beginEntity(capture, uuid(9), uuid(60), ArenaWorldLedger.EntityType.SPECTRAL_ARROW);
            ledger.beginEntity(capture, uuid(3), uuid(61), ArenaWorldLedger.EntityType.ARROW);
        }
        try (var ledger = new ArenaWorldLedger(directory)) {
            var rows = ledger.entities(operation(PlayerStateOperation.Kind.RESTORE, 80, 7, 81));
            assertEquals(List.of(uuid(3), uuid(9)), rows.stream().map(ArenaWorldLedger.Entity::entityId).toList());
            assertTrue(rows.stream().allMatch(row -> row.status() == ArenaWorldLedger.EntityStatus.PENDING));
            assertThrows(UnsupportedOperationException.class, rows::clear);
            var found = ledger.findEntity(uuid(9)).orElseThrow();
            assertEquals(uuid(4), found.sessionId());
            assertEquals(uuid(9), found.entityId());
            assertEquals(ArenaWorldLedger.EntityType.SPECTRAL_ARROW, found.type());
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING, found.status());
            assertTrue(ledger.findEntity(uuid(99)).isEmpty());
            assertEquals(ArenaWorldLedger.EntityStatus.CONFIRMED, ledger.confirmEntity(capture, uuid(3)).status());
        }
    }

    @Test void entityIdsCannotBeReboundAndRowsHaveA4096ResourceLimit() throws Exception {
        PlayerStateOperation capture = operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7);
        PlayerStateOperation otherPlayerCapture = captureOperationWith(uuid(22), capture.snapshotId(), uuid(24),
                capture.matchId(), uuid(27), uuid(7), GameKey.ARENA, capture.capturedAt());
        Path directory = temporary.resolve("bounds");
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(capture, MANIFEST); ledger.arm(capture);
            ledger.capture(otherPlayerCapture, MANIFEST); ledger.arm(otherPlayerCapture);
            ledger.beginEntity(capture, uuid(5000), uuid(6000), ArenaWorldLedger.EntityType.ARROW);
            assertThrows(IllegalStateException.class,
                    () -> ledger.beginEntity(otherPlayerCapture, uuid(5000), uuid(6000), ArenaWorldLedger.EntityType.ARROW));
            assertThrows(IllegalStateException.class,
                    () -> ledger.beginEntity(capture, uuid(5000), uuid(6001), ArenaWorldLedger.EntityType.ARROW));
        }
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("arena-world.sqlite"));
             var insert = database.prepareStatement("INSERT INTO arena_entity(entity_id, session_id, player_id, world_id, entity_type, status) VALUES (?, ?, ?, ?, 'ARROW', 'PENDING')")) {
            database.setAutoCommit(false);
            for (int i = 0; i < 4096; i++) {
                insert.setString(1, uuid(10_000 + i).toString()); insert.setString(2, uuid(4).toString());
                insert.setString(3, uuid(6).toString()); insert.setString(4, uuid(60).toString()); insert.addBatch();
            }
            insert.executeBatch(); database.commit();
        }
        try (var ledger = new ArenaWorldLedger(directory)) {
            assertThrows(IllegalStateException.class,
                    () -> ledger.beginEntity(capture, uuid(99_999), uuid(60), ArenaWorldLedger.EntityType.ARROW));
        }
    }

    @Test void playerReservationSurvivesReopenUntilTheLeaseIsRestored() {
        PlayerStateOperation first = operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7);
        PlayerStateOperation second = captureOperationWith(uuid(22), first.snapshotId(), uuid(24), first.matchId(),
                first.playerId(), uuid(7), GameKey.ARENA, first.capturedAt());
        Path directory = temporary.resolve("active-player");
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(first, MANIFEST);
            ledger.capture(second, MANIFEST); // A separately captured lease may wait in CAPTURED.
            assertEquals(ArenaWorldLedger.Status.ARMED, ledger.arm(first).status());
            assertThrows(IllegalStateException.class, () -> ledger.arm(second));
            assertEquals(ArenaWorldLedger.Status.CAPTURED, ledger.requireLease(second).status());
            assertThrows(IllegalStateException.class, () -> ledger.capture(captureOperationWith(
                    uuid(33), first.snapshotId(), uuid(25), first.matchId(), first.playerId(), uuid(7),
                    GameKey.ARENA, first.capturedAt()), MANIFEST));
            ledger.beginPurge(first);
            assertEquals(ArenaWorldLedger.Status.PURGED, ledger.completePurge(first).status());
        }
        try (var ledger = new ArenaWorldLedger(directory)) {
            assertEquals(ArenaWorldLedger.Status.PURGED, ledger.requireLease(first).status());
            assertThrows(IllegalStateException.class, () -> ledger.arm(second));
            assertEquals(ArenaWorldLedger.Status.RESTORED, ledger.markRestored(first).status());
            assertEquals(ArenaWorldLedger.Status.ARMED, ledger.arm(second).status());
        }
    }

    @Test void protectsPrivatePathsAndRejectsSymlinkFilesAndSidecars() throws Exception {
        Path directory = temporary.resolve("private");
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7), MANIFEST);
        }
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("arena-world.sqlite")));
        }
        Path directoryLink = temporary.resolve("directory-link");
        Files.createSymbolicLink(directoryLink, directory);
        assertThrows(PersistenceFailure.class, () -> new ArenaWorldLedger(directoryLink));
        Path ancestorTarget = Files.createDirectory(temporary.resolve("ancestor-target"));
        Path ancestorLink = temporary.resolve("ancestor-link");
        Files.createSymbolicLink(ancestorLink, ancestorTarget);
        assertThrows(PersistenceFailure.class, () -> new ArenaWorldLedger(ancestorLink.resolve("child")));
        Path fileLinkDirectory = Files.createDirectory(temporary.resolve("file-link-dir"));
        Files.createSymbolicLink(fileLinkDirectory.resolve("arena-world.sqlite"), directory.resolve("arena-world.sqlite"));
        assertThrows(PersistenceFailure.class, () -> new ArenaWorldLedger(fileLinkDirectory));
        Path sidecarDirectory = Files.createDirectory(temporary.resolve("sidecar-link-dir"));
        Files.createSymbolicLink(sidecarDirectory.resolve("arena-world.sqlite-wal"), directory.resolve("arena-world.sqlite"));
        assertThrows(PersistenceFailure.class, () -> new ArenaWorldLedger(sidecarDirectory));
    }

    @Test void rejectsUnversionedPopulatedFutureAndCorruptStoresButAcceptsFreshEmptyStore() throws Exception {
        Path fresh = temporary.resolve("fresh");
        try (var ledger = new ArenaWorldLedger(fresh)) {
            assertThrows(IllegalStateException.class, () -> ledger.requireLease(operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7)));
        }
        for (int version : new int[] {0, 2}) {
            Path directory = Files.createDirectory(temporary.resolve("schema-" + version));
            try (var database = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("arena-world.sqlite"));
                 var statement = database.createStatement()) {
                if (version == 0) statement.execute("CREATE TABLE foreign_data(value TEXT)");
                statement.execute("PRAGMA user_version = " + version);
            }
            assertThrows(PersistenceFailure.class, () -> new ArenaWorldLedger(directory));
        }
        Path corrupted = temporary.resolve("corrupt");
        try (var ledger = new ArenaWorldLedger(corrupted)) {
            ledger.capture(operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7), MANIFEST);
        }
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + corrupted.resolve("arena-world.sqlite"));
             var statement = database.createStatement()) {
            statement.executeUpdate("UPDATE arena_lease SET manifest = X'0102'");
        }
        try (var ledger = new ArenaWorldLedger(corrupted)) {
            assertThrows(IllegalStateException.class,
                    () -> ledger.requireLease(operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7)));
        }
        Path garbage = Files.createDirectory(temporary.resolve("garbage"));
        Files.writeString(garbage.resolve("arena-world.sqlite"), "not sqlite");
        assertThrows(PersistenceFailure.class, () -> new ArenaWorldLedger(garbage));
    }

    @Test void rejectsVersionOneStoresMissingEntityIdentityOrLeaseConstraints() throws Exception {
        assertTamperedEntitySchemaRejected("entity-no-uuid-key", """
                CREATE TABLE arena_entity (
                    entity_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    player_id TEXT NOT NULL,
                    world_id TEXT NOT NULL,
                    entity_type TEXT NOT NULL CHECK (entity_type IN ('ARROW','SPECTRAL_ARROW')),
                    status TEXT NOT NULL CHECK (status IN ('PENDING','CONFIRMED','REMOVED')),
                    FOREIGN KEY (session_id, player_id) REFERENCES arena_lease(session_id, player_id)
                )
                """);
        assertTamperedEntitySchemaRejected("entity-no-lease-fk", """
                CREATE TABLE arena_entity (
                    entity_id TEXT NOT NULL PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    player_id TEXT NOT NULL,
                    world_id TEXT NOT NULL,
                    entity_type TEXT NOT NULL CHECK (entity_type IN ('ARROW','SPECTRAL_ARROW')),
                    status TEXT NOT NULL CHECK (status IN ('PENDING','CONFIRMED','REMOVED'))
                )
                """);
    }

    @Test void rejectsForeignKeyOrphansInsertedWhileLedgerWasOffline() throws Exception {
        Path directory = temporary.resolve("orphan-entity");
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7), MANIFEST);
        }
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("arena-world.sqlite"));
             var statement = database.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate("""
                    INSERT INTO arena_entity(entity_id, session_id, player_id, world_id, entity_type, status)
                    VALUES ('00000000-0000-0000-0000-000000000008',
                            '00000000-0000-0000-0000-000000000099',
                            '00000000-0000-0000-0000-000000000098',
                            '00000000-0000-0000-0000-000000000097', 'ARROW', 'PENDING')
                    """);
        }

        assertThrows(PersistenceFailure.class, () -> new ArenaWorldLedger(directory));
    }

    private void assertTamperedEntitySchemaRejected(String name, String replacementSchema) throws Exception {
        Path directory = temporary.resolve(name);
        try (var ledger = new ArenaWorldLedger(directory)) { }
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("arena-world.sqlite"));
             var statement = database.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.execute("DROP TABLE arena_entity");
            statement.executeUpdate(replacementSchema);
            statement.execute("CREATE INDEX arena_entity_lease ON arena_entity(session_id, player_id, entity_id)");
        }
        assertThrows(PersistenceFailure.class, () -> new ArenaWorldLedger(directory));
    }

    private static PlayerStateOperation operation(PlayerStateOperation.Kind kind, long operationId, long captureId, long connectionId) {
        UUID captureOperation = uuid(captureId);
        return new PlayerStateOperation(kind, kind == PlayerStateOperation.Kind.CAPTURE ? captureOperation : uuid(operationId), captureOperation, uuid(3), uuid(4), uuid(5), uuid(6),
                uuid(7), uuid(connectionId), GameKey.ARENA, Instant.parse("2026-10-04T11:12:13.123456789Z"));
    }

    private static PlayerStateOperation operationWith(PlayerStateOperation basis, UUID captureId, UUID snapshotId,
            UUID sessionId, UUID matchId, UUID playerId, UUID epoch, GameKey game, Instant capturedAt) {
        return new PlayerStateOperation(PlayerStateOperation.Kind.PURGE, uuid(90), captureId, snapshotId,
                sessionId, matchId, playerId, epoch, uuid(91), game, capturedAt);
    }

    private static PlayerStateOperation captureOperationWith(UUID captureId, UUID snapshotId,
            UUID sessionId, UUID matchId, UUID playerId, UUID epoch, GameKey game, Instant capturedAt) {
        return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, captureId, captureId, snapshotId,
                sessionId, matchId, playerId, epoch, epoch, game, capturedAt);
    }

    private static UUID uuid(long value) { return new UUID(0, value); }
}
