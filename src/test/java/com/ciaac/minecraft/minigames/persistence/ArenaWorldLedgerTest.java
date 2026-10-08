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
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArenaWorldLedgerTest {
    @TempDir Path temporary;
    private static final byte[] MANIFEST = {10, 20, 30};

    @BeforeEach void canonicalTemporaryDirectory() throws Exception { temporary = temporary.toRealPath(); }

    @Test void buildPlotLeasesSurviveReopenButCanNeverOwnEntities() {
        Path directory = temporary.resolve("build-plots");
        var capture = operationForGame(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7, GameKey.BUILD_BATTLE);
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(capture, MANIFEST);
            assertEquals(1, ledger.unfinishedLeases(GameKey.BUILD_BATTLE).size());
            assertTrue(ledger.unfinishedLeases(GameKey.ARENA).isEmpty());
            assertEquals(1, ledger.leasesForMatch(capture.matchId(), GameKey.BUILD_BATTLE).size());
            ledger.arm(capture);
            for (var type : ArenaWorldLedger.EntityType.values())
                assertThrows(IllegalStateException.class, () -> ledger.beginEntity(capture, uuid(20), uuid(21), type));
        }
        try (var ledger = new ArenaWorldLedger(directory)) {
            assertEquals(ArenaWorldLedger.Status.ARMED, ledger.requireLease(capture).status());
            ledger.beginPurge(capture);
            ledger.completePurge(capture);
            assertEquals(1, ledger.unfinishedLeases(GameKey.BUILD_BATTLE).size());
            ledger.markRestored(capture);
            assertTrue(ledger.unfinishedLeases(GameKey.BUILD_BATTLE).isEmpty());
            assertEquals(1, ledger.leasesForMatch(capture.matchId(), GameKey.BUILD_BATTLE).size());
        }
    }

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
                    capture.playerId(), uuid(7), GameKey.COLOR_FLOOR, capture.capturedAt())));
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

    @Test void archeryLeaseEntityLifecycleSurvivesSqliteReopen() {
        PlayerStateOperation capture = operationForGame(
                PlayerStateOperation.Kind.CAPTURE, 2, 7, 7, GameKey.ARCHERY_RANGE);
        Path directory = temporary.resolve("archery-lifecycle");
        UUID arrowId = uuid(52);
        UUID worldId = uuid(62);

        try (var ledger = new ArenaWorldLedger(directory)) {
            assertEquals(ArenaWorldLedger.Status.CAPTURED, ledger.capture(capture, MANIFEST).status());
            assertEquals(ArenaWorldLedger.Status.ARMED, ledger.arm(capture).status());
        }
        try (var ledger = new ArenaWorldLedger(directory)) {
            var lease = ledger.requireLease(operationForGame(
                    PlayerStateOperation.Kind.PURGE, 71, 7, 81, GameKey.ARCHERY_RANGE));
            assertEquals(GameKey.ARCHERY_RANGE, lease.capture().game());
            assertArrayEquals(MANIFEST, lease.manifest());
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    ledger.beginEntity(capture, arrowId, worldId, ArenaWorldLedger.EntityType.ARROW).status());
        }
        try (var ledger = new ArenaWorldLedger(directory)) {
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    ledger.findEntity(arrowId).orElseThrow().status());
            assertEquals(ArenaWorldLedger.EntityStatus.CONFIRMED,
                    ledger.confirmEntity(capture, arrowId).status());
            assertEquals(ArenaWorldLedger.Status.PURGING,
                    ledger.beginPurge(operationForGame(
                            PlayerStateOperation.Kind.PURGE, 72, 7, 82, GameKey.ARCHERY_RANGE)).status());
            assertThrows(IllegalStateException.class, () -> ledger.completePurge(capture));
            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED, ledger.markRemoved(capture, arrowId).status());
            assertEquals(ArenaWorldLedger.Status.PURGED, ledger.completePurge(capture).status());
        }
        try (var ledger = new ArenaWorldLedger(directory)) {
            assertEquals(ArenaWorldLedger.Status.RESTORED,
                    ledger.markRestored(operationForGame(
                            PlayerStateOperation.Kind.RESTORE, 73, 7, 83, GameKey.ARCHERY_RANGE)).status());
            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED,
                    ledger.findEntity(arrowId).orElseThrow().status());
        }
    }

    @Test void arenaAndArcheryLeasesCannotAliasCaptureOrLookupIdentity() {
        PlayerStateOperation arena = operationForGame(
                PlayerStateOperation.Kind.CAPTURE, 2, 7, 7, GameKey.ARENA);
        PlayerStateOperation archery = operationForGame(
                PlayerStateOperation.Kind.CAPTURE, 2, 7, 7, GameKey.ARCHERY_RANGE);
        try (var ledger = new ArenaWorldLedger(temporary.resolve("cross-game-identity"))) {
            ledger.capture(arena, MANIFEST);
            assertThrows(IllegalStateException.class, () -> ledger.requireLease(archery));
            assertThrows(IllegalStateException.class, () -> ledger.capture(archery, MANIFEST));
            assertEquals(GameKey.ARENA, ledger.requireLease(arena).capture().game());
            for (GameKey game : GameKey.values()) {
                if (game == GameKey.ARENA || game == GameKey.ARCHERY_RANGE
                        || game == GameKey.ANVIL_DODGE || game == GameKey.ELYTRA_RINGS
                        || game == GameKey.BUILD_BATTLE) continue;
                PlayerStateOperation unsupported = operationForGame(
                        PlayerStateOperation.Kind.CAPTURE, 2, 7, 7, game);
                assertThrows(IllegalArgumentException.class, () -> ledger.capture(unsupported, MANIFEST), game.id());
                assertThrows(IllegalArgumentException.class, () -> ledger.requireLease(unsupported), game.id());
            }
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
        for (int version : new int[] {0, 4}) {
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

    @Test void freshDatabaseUsesSchemaThreeAndProcessIdentityRequiresPositiveValues() throws Exception {
        Path directory = temporary.resolve("schema-two");
        try (var ledger = new ArenaWorldLedger(directory)) {
            assertThrows(IllegalStateException.class, () -> ledger.requireLease(operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7)));
        }
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("arena-world.sqlite"));
             var statement = database.createStatement();
             var version = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(version.next());
            assertEquals(3, version.getInt(1));
            try (var table = statement.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='arena_entity_process'")) {
                assertTrue(table.next());
                assertTrue(table.getString(1).contains("REFERENCES arena_entity(entity_id)"));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new NativeProcessIdentity(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new NativeProcessIdentity(1, 0));
        assertTrue(NativeProcessIdentity.current().pid() > 0);
        assertTrue(NativeProcessIdentity.current().startedAtEpochMillis() > 0);
    }

    @Test void exactSchemaOneMigrationPreservesRowsAndLeavesLegacyEntitiesUnattested() throws Exception {
        Path directory = temporary.resolve("migrate-v1");
        PlayerStateOperation capture = operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7);
        UUID legacyEntity = uuid(350);
        byte[] identityBefore;
        byte[] manifestBefore;
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(capture, MANIFEST);
            ledger.arm(capture);
            ledger.beginEntity(capture, legacyEntity, uuid(60), ArenaWorldLedger.EntityType.ARROW);
        }
        Path databasePath = directory.resolve("arena-world.sqlite");
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = database.createStatement()) {
            try (var row = statement.executeQuery("SELECT capture_identity, manifest FROM arena_lease WHERE session_id='" + uuid(4) + "'")) {
                assertTrue(row.next());
                identityBefore = row.getBytes(1);
                manifestBefore = row.getBytes(2);
            }
        }
        downgradeEntitySchema(databasePath, 1);
        try (var ledger = new ArenaWorldLedger(directory)) {
            assertArrayEquals(MANIFEST, ledger.requireLease(capture).manifest());
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING, ledger.findEntity(legacyEntity).orElseThrow().status());
            assertTrue(ledger.nonPersistentProcess(legacyEntity).isEmpty());
            assertThrows(IllegalStateException.class, () -> ledger.beginEntity(capture, legacyEntity, uuid(60),
                    ArenaWorldLedger.EntityType.ARROW, new NativeProcessIdentity(123, 456)));
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    ledger.beginEntity(capture, legacyEntity, uuid(60), ArenaWorldLedger.EntityType.ARROW).status());
        }
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = database.createStatement();
             var version = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(version.next());
            assertEquals(3, version.getInt(1));
            try (var row = statement.executeQuery("SELECT capture_identity, manifest FROM arena_lease WHERE session_id='" + uuid(4) + "'")) {
                assertTrue(row.next());
                assertArrayEquals(identityBefore, row.getBytes(1));
                assertArrayEquals(manifestBefore, row.getBytes(2));
            }
        }
    }

    @Test void exactSchemaTwoMigrationPreservesConfirmedArrowAndProcessProof() throws Exception {
        Path directory = temporary.resolve("migrate-v2");
        Path databasePath = directory.resolve("arena-world.sqlite");
        PlayerStateOperation capture = operationForGame(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7,
                GameKey.ARCHERY_RANGE);
        UUID entityId = uuid(353);
        NativeProcessIdentity proof = new NativeProcessIdentity(321, 654);
        byte[] identityBefore;
        byte[] manifestBefore;
        StoredEntity entityBefore;
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(capture, MANIFEST);
            ledger.arm(capture);
            ledger.beginEntity(capture, entityId, uuid(60), ArenaWorldLedger.EntityType.SPECTRAL_ARROW, proof);
            ledger.confirmEntity(capture, entityId);
        }
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = database.createStatement()) {
            try (var row = statement.executeQuery("SELECT capture_identity, manifest FROM arena_lease WHERE session_id='" + capture.sessionId() + "'")) {
                assertTrue(row.next());
                identityBefore = row.getBytes(1);
                manifestBefore = row.getBytes(2);
            }
            entityBefore = readStoredEntity(statement, entityId);
        }
        downgradeEntitySchema(databasePath, 2);

        try (var ledger = new ArenaWorldLedger(directory)) {
            assertArrayEquals(MANIFEST, ledger.requireLease(capture).manifest());
            assertEquals(ArenaWorldLedger.EntityStatus.CONFIRMED, ledger.findEntity(entityId).orElseThrow().status());
            assertEquals(java.util.Optional.of(proof), ledger.nonPersistentProcess(entityId));
            assertEquals(ArenaWorldLedger.EntityStatus.CONFIRMED, ledger.beginEntity(capture, entityId, uuid(60),
                    ArenaWorldLedger.EntityType.SPECTRAL_ARROW, proof).status());
        }
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = database.createStatement();
             var version = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(version.next());
            assertEquals(3, version.getInt(1));
            try (var row = statement.executeQuery("SELECT capture_identity, manifest FROM arena_lease WHERE session_id='" + capture.sessionId() + "'")) {
                assertTrue(row.next());
                assertArrayEquals(identityBefore, row.getBytes(1));
                assertArrayEquals(manifestBefore, row.getBytes(2));
            }
            assertEquals(entityBefore, readStoredEntity(statement, entityId));
            try (var row = statement.executeQuery("SELECT process_pid, process_started_at FROM arena_entity_process WHERE entity_id='" + entityId + "'")) {
                assertTrue(row.next());
                assertEquals(proof.pid(), row.getLong(1));
                assertEquals(proof.startedAtEpochMillis(), row.getLong(2));
            }
        }
    }

    @Test void newEntityKindsRequireTheirExactCaptureGameAndClaimsRecheckCompatibility() throws Exception {
        Map<GameKey, Set<ArenaWorldLedger.EntityType>> validTypes = Map.of(
                GameKey.ARENA, Set.of(ArenaWorldLedger.EntityType.ARROW, ArenaWorldLedger.EntityType.SPECTRAL_ARROW),
                GameKey.ARCHERY_RANGE, Set.of(ArenaWorldLedger.EntityType.ARROW, ArenaWorldLedger.EntityType.SPECTRAL_ARROW),
                GameKey.ANVIL_DODGE, Set.of(ArenaWorldLedger.EntityType.ANVIL_MARKER),
                GameKey.ELYTRA_RINGS, Set.of(ArenaWorldLedger.EntityType.ELYTRA_FIREWORK));
        long id = 800;
        for (var entry : validTypes.entrySet()) {
            GameKey game = entry.getKey();
            Set<ArenaWorldLedger.EntityType> valid = entry.getValue();
            PlayerStateOperation capture = operationForGame(PlayerStateOperation.Kind.CAPTURE,
                    id, id + 10, id + 20, game);
            Path directory = temporary.resolve("game-type-" + game.id());
            try (var ledger = new ArenaWorldLedger(directory)) {
                ledger.capture(capture, MANIFEST);
                ledger.arm(capture);
                for (ArenaWorldLedger.EntityType type : ArenaWorldLedger.EntityType.values()) {
                    if (!valid.contains(type)) {
                        UUID rejectedEntity = uuid(id + type.ordinal() + 100);
                        assertThrows(IllegalStateException.class, () -> ledger.beginEntity(capture,
                                rejectedEntity, uuid(60), type));
                    }
                }
                int index = 0;
                for (ArenaWorldLedger.EntityType type : valid) {
                    UUID entity = uuid(id + 30 + index++);
                    assertEquals(type, ledger.beginEntity(capture, entity, uuid(60), type).type());
                }
            }
            id += 100;
        }

        PlayerStateOperation arena = operation(PlayerStateOperation.Kind.CAPTURE, 50, 60, 70);
        Path corrupt = temporary.resolve("cross-game-row");
        UUID entityId = uuid(71);
        try (var ledger = new ArenaWorldLedger(corrupt)) {
            ledger.capture(arena, MANIFEST);
            ledger.arm(arena);
            ledger.beginEntity(arena, entityId, uuid(60), ArenaWorldLedger.EntityType.ARROW);
            try (var database = DriverManager.getConnection("jdbc:sqlite:" + corrupt.resolve("arena-world.sqlite"));
                 var statement = database.createStatement()) {
                statement.executeUpdate("UPDATE arena_entity SET entity_type='ANVIL_MARKER' WHERE entity_id='" + entityId + "'");
            }
            assertThrows(IllegalStateException.class, () -> ledger.findEntity(entityId));
            assertThrows(IllegalStateException.class, () -> ledger.entities(arena));
        }
    }

    @Test void leasesForMatchFiltersDecodedGameAndMatchKeepsAllPhasesAndSortsSessions() {
        Path directory = temporary.resolve("match-leases");
        UUID targetMatch = uuid(1_000);
        PlayerStateOperation restored = captureOperationForMatch(10, 101, 201, targetMatch, GameKey.ANVIL_DODGE);
        PlayerStateOperation armed = captureOperationForMatch(20, 102, 202, targetMatch, GameKey.ANVIL_DODGE);
        PlayerStateOperation otherGame = captureOperationForMatch(30, 103, 203, targetMatch, GameKey.ELYTRA_RINGS);
        PlayerStateOperation otherMatch = captureOperationForMatch(40, 104, 204, uuid(1_001), GameKey.ANVIL_DODGE);
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(restored, MANIFEST); ledger.arm(restored); ledger.beginPurge(restored);
            ledger.completePurge(restored); ledger.markRestored(restored);
            ledger.capture(armed, MANIFEST); ledger.arm(armed);
            ledger.capture(otherGame, MANIFEST);
            ledger.capture(otherMatch, MANIFEST);

            List<ArenaWorldLedger.Lease> leases = ledger.leasesForMatch(targetMatch, GameKey.ANVIL_DODGE);
            assertEquals(List.of(restored.sessionId(), armed.sessionId()),
                    leases.stream().map(lease -> lease.capture().sessionId()).toList());
            assertEquals(List.of(ArenaWorldLedger.Status.RESTORED, ArenaWorldLedger.Status.ARMED),
                    leases.stream().map(ArenaWorldLedger.Lease::status).toList());
            assertThrows(UnsupportedOperationException.class, leases::clear);
            assertTrue(ledger.leasesForMatch(uuid(1_002), GameKey.ANVIL_DODGE).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> ledger.leasesForMatch(targetMatch, GameKey.COLOR_FLOOR));
        }
    }

    @Test void leasesForMatchFailsClosedOnMalformedUnrelatedLeaseIdentity() throws Exception {
        Path directory = temporary.resolve("malformed-unrelated-match-lease");
        PlayerStateOperation target = captureOperationForMatch(10, 101, 201, uuid(1_010), GameKey.ANVIL_DODGE);
        PlayerStateOperation unrelated = captureOperationForMatch(20, 102, 202, uuid(1_011), GameKey.ANVIL_DODGE);
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(target, MANIFEST);
            ledger.capture(unrelated, MANIFEST);
        }
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("arena-world.sqlite"));
             var statement = database.createStatement()) {
            statement.executeUpdate("UPDATE arena_lease SET capture_identity=X'010203' WHERE session_id='" + unrelated.sessionId() + "'");
        }
        try (var ledger = new ArenaWorldLedger(directory)) {
            assertThrows(IllegalStateException.class,
                    () -> ledger.leasesForMatch(target.matchId(), GameKey.ANVIL_DODGE));
        }
    }

    @Test void processProofIsDurableAndReplayMustMatchProofExactly() throws Exception {
        Path directory = temporary.resolve("process-proof");
        PlayerStateOperation capture = operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7);
        UUID entity = uuid(351);
        NativeProcessIdentity process = new NativeProcessIdentity(321, 654);
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(capture, MANIFEST);
            ledger.arm(capture);
            ledger.beginEntity(capture, entity, uuid(60), ArenaWorldLedger.EntityType.SPECTRAL_ARROW, process);
            assertEquals(java.util.Optional.of(process), ledger.nonPersistentProcess(entity));
            assertThrows(IllegalStateException.class, () -> ledger.beginEntity(capture, entity, uuid(60),
                    ArenaWorldLedger.EntityType.SPECTRAL_ARROW));
            assertThrows(IllegalStateException.class, () -> ledger.beginEntity(capture, entity, uuid(60),
                    ArenaWorldLedger.EntityType.SPECTRAL_ARROW, new NativeProcessIdentity(321, 655)));
            assertThrows(IllegalStateException.class, () -> ledger.nonPersistentProcess(uuid(999)));
        }
        try (var ledger = new ArenaWorldLedger(directory)) {
            assertEquals(java.util.Optional.of(process), ledger.nonPersistentProcess(entity));
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING, ledger.beginEntity(capture, entity, uuid(60),
                    ArenaWorldLedger.EntityType.SPECTRAL_ARROW, process).status());
        }
    }

    @Test void malformedVersionOneSchemaIsRejectedBeforeMigrationMutation() throws Exception {
        Path directory = temporary.resolve("malformed-v1");
        PlayerStateOperation capture = operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7);
        UUID entity = uuid(354);
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(capture, MANIFEST);
            ledger.arm(capture);
            ledger.beginEntity(capture, entity, uuid(60), ArenaWorldLedger.EntityType.ARROW);
        }
        Path databasePath = directory.resolve("arena-world.sqlite");
        downgradeEntitySchema(databasePath, 1);
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = database.createStatement()) {
            statement.execute("DROP INDEX arena_entity_lease");
        }
        assertThrows(PersistenceFailure.class, () -> new ArenaWorldLedger(directory));
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = database.createStatement();
             var version = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(version.next());
            assertEquals(1, version.getInt(1));
            try (var table = statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='arena_entity_process'")) {
                assertTrue(!table.next());
            }
            try (var table = statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='arena_entity_migration'")) {
                assertTrue(!table.next());
            }
            try (var lease = statement.executeQuery("SELECT count(*) FROM arena_lease")) {
                assertTrue(lease.next());
                assertEquals(1, lease.getInt(1));
            }
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,
                    ArenaWorldLedger.EntityStatus.valueOf(readStoredEntity(statement, entity).status()));
        }
    }

    @Test void versionOneMigrationRejectsForeignKeyDamageBeforeSchemaMutation() throws Exception {
        Path directory = temporary.resolve("migration-fk-preflight");
        Path databasePath = directory.resolve("arena-world.sqlite");
        PlayerStateOperation capture = operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7);
        UUID entity = uuid(355);
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(capture, MANIFEST);
            ledger.arm(capture);
            ledger.beginEntity(capture, entity, uuid(60), ArenaWorldLedger.EntityType.ARROW);
        }
        downgradeEntitySchema(databasePath, 1);
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = database.createStatement()) {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.executeUpdate("UPDATE arena_entity SET session_id='" + uuid(999) + "' WHERE entity_id='" + entity + "'");
        }
        assertThrows(PersistenceFailure.class, () -> new ArenaWorldLedger(directory));
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = database.createStatement();
             var version = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(version.next());
            assertEquals(1, version.getInt(1));
            try (var row = statement.executeQuery("SELECT session_id FROM arena_entity WHERE entity_id='" + entity + "'")) {
                assertTrue(row.next());
                assertEquals(uuid(999).toString(), row.getString(1));
            }
            try (var temporaryTable = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE name LIKE 'arena_entity%_migration'")) {
                assertTrue(temporaryTable.next());
                assertEquals(0, temporaryTable.getInt(1));
            }
        }
    }

    @Test void corruptedProcessRowsFailClosedOnReopen() throws Exception {
        Path directory = temporary.resolve("corrupt-process-proof");
        PlayerStateOperation capture = operation(PlayerStateOperation.Kind.CAPTURE, 2, 7, 7);
        try (var ledger = new ArenaWorldLedger(directory)) {
            ledger.capture(capture, MANIFEST);
            ledger.arm(capture);
            ledger.beginEntity(capture, uuid(352), uuid(60), ArenaWorldLedger.EntityType.ARROW,
                    new NativeProcessIdentity(321, 654));
        }
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("arena-world.sqlite"));
             var statement = database.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE arena_entity_process SET process_pid=0 WHERE entity_id='" + uuid(352) + "'");
        }
        assertThrows(PersistenceFailure.class, () -> new ArenaWorldLedger(directory));
    }

    @Test void rejectsVersionOneStoresMissingEntityIdentityOrLeaseConstraints() throws Exception {
        assertTamperedEntitySchemaRejected("entity-no-uuid-key", """
                CREATE TABLE arena_entity (
                    entity_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    player_id TEXT NOT NULL,
                    world_id TEXT NOT NULL,
                    entity_type TEXT NOT NULL CHECK (entity_type IN ('ARROW','SPECTRAL_ARROW','ANVIL_MARKER','ELYTRA_FIREWORK')),
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
                    entity_type TEXT NOT NULL CHECK (entity_type IN ('ARROW','SPECTRAL_ARROW','ANVIL_MARKER','ELYTRA_FIREWORK')),
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
        return operationForGame(kind, operationId, captureId, connectionId, GameKey.ARENA);
    }

    private static PlayerStateOperation operationForGame(
            PlayerStateOperation.Kind kind, long operationId, long captureId, long connectionId, GameKey game) {
        UUID captureOperation = uuid(captureId);
        return new PlayerStateOperation(kind, kind == PlayerStateOperation.Kind.CAPTURE ? captureOperation : uuid(operationId), captureOperation, uuid(3), uuid(4), uuid(5), uuid(6),
                uuid(7), (kind == PlayerStateOperation.Kind.ENTER || kind == PlayerStateOperation.Kind.CAPTURE)
                        ? uuid(7) : uuid(connectionId), game, Instant.parse("2026-10-04T11:12:13.123456789Z"));
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

    private static PlayerStateOperation captureOperationForMatch(long id, long sessionId, long playerId,
            UUID matchId, GameKey game) {
        UUID captureId = uuid(id);
        UUID epoch = uuid(id + 1_000);
        return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, captureId, captureId, uuid(id + 2_000),
                uuid(sessionId), matchId, uuid(playerId), epoch, epoch, game,
                Instant.parse("2026-10-04T11:12:13.123456789Z").plusSeconds(id));
    }

    private static StoredEntity readStoredEntity(java.sql.Statement statement, UUID entityId) throws SQLException {
        try (var row = statement.executeQuery("SELECT entity_id,session_id,player_id,world_id,entity_type,status "
                + "FROM arena_entity WHERE entity_id='" + entityId + "'")) {
            if (!row.next()) throw new AssertionError("Expected stored entity " + entityId);
            StoredEntity entity = new StoredEntity(row.getString(1), row.getString(2), row.getString(3),
                    row.getString(4), row.getString(5), row.getString(6));
            if (row.next()) throw new AssertionError("Duplicate stored entity " + entityId);
            return entity;
        }
    }

    /** Reconstructs an exact historical entity schema while preserving entity and optional proof rows. */
    private static void downgradeEntitySchema(Path databasePath, int version) throws Exception {
        if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported fixture version");
        List<StoredEntity> entities = new ArrayList<>();
        Map<String, NativeProcessIdentity> proofs = new HashMap<>();
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             var statement = database.createStatement()) {
            try (var rows = statement.executeQuery("SELECT entity_id,session_id,player_id,world_id,entity_type,status FROM arena_entity")) {
                while (rows.next()) entities.add(new StoredEntity(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6)));
            }
            try (var rows = statement.executeQuery("SELECT entity_id,process_pid,process_started_at FROM arena_entity_process")) {
                while (rows.next()) proofs.put(rows.getString(1),
                        new NativeProcessIdentity(rows.getLong(2), rows.getLong(3)));
            } catch (SQLException absentOnV1) {
                if (version != 1) throw absentOnV1;
            }
            statement.execute("PRAGMA foreign_keys=OFF");
            database.setAutoCommit(false);
            try {
                statement.executeUpdate("DROP TABLE IF EXISTS arena_entity_process");
                statement.executeUpdate("DROP TABLE arena_entity");
                statement.executeUpdate("""
                        CREATE TABLE arena_entity (
                            entity_id TEXT NOT NULL PRIMARY KEY,
                            session_id TEXT NOT NULL,
                            player_id TEXT NOT NULL,
                            world_id TEXT NOT NULL,
                            entity_type TEXT NOT NULL CHECK (entity_type IN ('ARROW','SPECTRAL_ARROW')),
                            status TEXT NOT NULL CHECK (status IN ('PENDING','CONFIRMED','REMOVED')),
                            FOREIGN KEY (session_id, player_id) REFERENCES arena_lease(session_id, player_id)
                        )
                        """);
                statement.executeUpdate("CREATE INDEX arena_entity_lease ON arena_entity(session_id, player_id, entity_id)");
                if (version == 2) {
                    statement.executeUpdate("""
                            CREATE TABLE arena_entity_process (
                                entity_id TEXT NOT NULL PRIMARY KEY REFERENCES arena_entity(entity_id),
                                process_pid INTEGER NOT NULL CHECK (typeof(process_pid) = 'integer' AND process_pid > 0),
                                process_started_at INTEGER NOT NULL CHECK (typeof(process_started_at) = 'integer' AND process_started_at > 0)
                            )
                            """);
                }
                try (var insert = database.prepareStatement("INSERT INTO arena_entity"
                        + "(entity_id,session_id,player_id,world_id,entity_type,status) VALUES(?,?,?,?,?,?)")) {
                    for (StoredEntity entity : entities) {
                        insert.setString(1, entity.entityId()); insert.setString(2, entity.sessionId());
                        insert.setString(3, entity.playerId()); insert.setString(4, entity.worldId());
                        insert.setString(5, entity.type()); insert.setString(6, entity.status()); insert.addBatch();
                    }
                    insert.executeBatch();
                }
                if (version == 2) {
                    try (var insert = database.prepareStatement("INSERT INTO arena_entity_process"
                            + "(entity_id,process_pid,process_started_at) VALUES(?,?,?)")) {
                        for (var entry : proofs.entrySet()) {
                            insert.setString(1, entry.getKey()); insert.setLong(2, entry.getValue().pid());
                            insert.setLong(3, entry.getValue().startedAtEpochMillis()); insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                }
                statement.execute("PRAGMA user_version=" + version);
                database.commit();
            } catch (Exception failure) {
                database.rollback();
                throw failure;
            } finally {
                database.setAutoCommit(true);
            }
        }
    }

    private record StoredEntity(String entityId, String sessionId, String playerId, String worldId,
            String type, String status) { }

    private static UUID uuid(long value) { return new UUID(0, value); }
}
