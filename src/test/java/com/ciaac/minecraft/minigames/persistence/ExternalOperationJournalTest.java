package com.ciaac.minecraft.minigames.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

class ExternalOperationJournalTest {
    @TempDir Path temporary;
    private static final byte[] REQUEST = {1, 2, 3};
    private static final byte[] RESULT = {4, 5, 6};

    @BeforeEach void canonicalTemporaryDirectory() throws Exception {
        temporary = temporary.toRealPath();
    }

    @Test void unfinishedMutationRemainsPendingAfterCrashAndReconnect() {
        var operation = operation(PlayerStateOperation.Kind.RESTORE, uuid(8), uuid(7));
        Path path = temporary.resolve("journal");
        try (var journal = new ExternalOperationJournal(path)) {
            assertEquals(ExternalOperationJournal.State.NEW, journal.begin("test-provider", 1, operation, REQUEST));
        }
        try (var journal = new ExternalOperationJournal(path)) {
            var reconnect = operation(PlayerStateOperation.Kind.RESTORE, uuid(8), uuid(9));
            assertEquals(ExternalOperationJournal.State.PENDING, journal.begin("test-provider", 1, reconnect, REQUEST));
            assertThrows(IllegalStateException.class,
                    () -> journal.committedResult("test-provider", 1, reconnect, REQUEST));
        }
    }

    @Test void completedOperationAndDefensiveResultSurviveReopen() {
        var operation = operation(PlayerStateOperation.Kind.RESTORE, uuid(8), uuid(7));
        Path path = temporary.resolve("journal");
        byte[] result = RESULT.clone();
        try (var journal = new ExternalOperationJournal(path)) {
            journal.begin("test-provider", 1, operation, REQUEST);
            journal.commit("test-provider", 1, operation, REQUEST, result);
            result[0] = 99;
            journal.commit("test-provider", 1, operation, REQUEST, RESULT);
        }
        try (var journal = new ExternalOperationJournal(path)) {
            var reconnect = operation(PlayerStateOperation.Kind.RESTORE, uuid(8), uuid(9));
            assertEquals(ExternalOperationJournal.State.COMMITTED, journal.begin("test-provider", 1, reconnect, REQUEST));
            byte[] read = journal.committedResult("test-provider", 1, reconnect, REQUEST);
            assertArrayEquals(RESULT, read);
            read[1] = 99;
            assertArrayEquals(RESULT, journal.committedResult("test-provider", 1, reconnect, REQUEST));
            assertThrows(IllegalStateException.class,
                    () -> journal.commit("test-provider", 1, reconnect, REQUEST, new byte[] {9}));
        }
    }

    @Test void rejectsConflictingRequestIdentityVersionAndPhase() {
        var original = operation(PlayerStateOperation.Kind.RESTORE, uuid(8), uuid(7));
        try (var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            journal.begin("test-provider", 1, original, REQUEST);
            assertThrows(IllegalStateException.class, () -> journal.begin("test-provider", 2, original, REQUEST));
            assertThrows(IllegalStateException.class, () -> journal.begin("test-provider", 1, original, new byte[] {9}));
            var purge = operation(PlayerStateOperation.Kind.PURGE, uuid(8), uuid(7));
            assertThrows(IllegalStateException.class, () -> journal.begin("test-provider", 1, purge, REQUEST));
            var foreign = new PlayerStateOperation(original.kind(), original.operationId(), original.captureOperationId(),
                    original.snapshotId(), original.sessionId(), original.matchId(), uuid(99), original.capturedConnectionId(),
                    original.connectionId(), original.game(), original.capturedAt());
            assertThrows(IllegalStateException.class, () -> journal.begin("test-provider", 1, foreign, REQUEST));
            assertThrows(IllegalStateException.class,
                    () -> journal.commit("test-provider", 1, operation(original.kind(), uuid(99), uuid(7)), REQUEST, RESULT));
            assertEquals(ExternalOperationJournal.State.NEW, journal.begin("different-provider", 1, original, REQUEST));
        }
    }

    @Test void refusesUnversionedPopulatedAndFutureStoresWithoutChangingThem() throws Exception {
        Class.forName("org.sqlite.JDBC");
        for (int version : new int[] {0, 2}) {
            Path path = Files.createDirectory(temporary.resolve("schema-" + version));
            try (var database = DriverManager.getConnection("jdbc:sqlite:" + path.resolve("operations.sqlite"));
                 var statement = database.createStatement()) {
                statement.execute("CREATE TABLE unrelated(value TEXT)");
                statement.execute("INSERT INTO unrelated VALUES ('preserve')");
                statement.execute("PRAGMA user_version = " + version);
            }
            assertThrows(PersistenceFailure.class, () -> new ExternalOperationJournal(path));
            try (var database = DriverManager.getConnection("jdbc:sqlite:" + path.resolve("operations.sqlite"));
                 var statement = database.createStatement();
                 var row = statement.executeQuery("SELECT value FROM unrelated")) {
                assertEquals("preserve", row.getString(1));
            }
        }
    }

    @Test void boundsPayloadAndProtectsPrivateStoreAndSymlinkTargets() throws Exception {
        Path path = temporary.resolve("journal");
        try (var journal = new ExternalOperationJournal(path)) {
            var operation = operation(PlayerStateOperation.Kind.RESTORE, uuid(8), uuid(7));
            assertThrows(IllegalArgumentException.class,
                    () -> journal.begin("test-provider", 1, operation, new byte[8 * 1024 * 1024 + 1]));
            assertThrows(IllegalArgumentException.class, () -> journal.begin("INVALID", 1, operation, REQUEST));
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(path));
                assertEquals(PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(path.resolve("operations.sqlite")));
            }
        }
        Path link = temporary.resolve("link");
        Files.createSymbolicLink(link, path);
        assertThrows(PersistenceFailure.class, () -> new ExternalOperationJournal(link));
        Path other = Files.createDirectory(temporary.resolve("other"));
        Files.createSymbolicLink(other.resolve("operations.sqlite"), path.resolve("operations.sqlite"));
        assertThrows(PersistenceFailure.class, () -> new ExternalOperationJournal(other));
    }

    @Test void corruptedResultCannotBeTreatedAsACompletedCapture() throws Exception {
        Path path = temporary.resolve("journal");
        var capture = operation(PlayerStateOperation.Kind.CAPTURE, uuid(2), uuid(7));
        try (var journal = new ExternalOperationJournal(path)) {
            journal.begin("test-provider", 1, capture, new byte[0]);
            journal.commit("test-provider", 1, capture, new byte[0], RESULT);
        }
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + path.resolve("operations.sqlite"));
             var statement = database.createStatement()) {
            statement.executeUpdate("UPDATE external_operation SET result = X'9999'");
        }
        try (var journal = new ExternalOperationJournal(path)) {
            assertThrows(IllegalStateException.class, () -> journal.begin("test-provider", 1, capture, new byte[0]));
            assertThrows(IllegalStateException.class, () -> journal.committedResult("test-provider", 1, capture, new byte[0]));
        }
    }

    @Test void everyCapturedIdentityFieldIsBoundToTheOperation() {
        var original = operation(PlayerStateOperation.Kind.RESTORE, uuid(8), uuid(7));
        try (var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            journal.begin("test-provider", 1, original, REQUEST);
            for (int changed = 0; changed < 8; changed++) {
                var foreign = new PlayerStateOperation(original.kind(), original.operationId(),
                        changed == 0 ? uuid(99) : original.captureOperationId(),
                        changed == 1 ? uuid(99) : original.snapshotId(),
                        changed == 2 ? uuid(99) : original.sessionId(),
                        changed == 3 ? uuid(99) : original.matchId(),
                        changed == 4 ? uuid(99) : original.playerId(),
                        changed == 5 ? uuid(99) : original.capturedConnectionId(), original.connectionId(),
                        changed == 6 ? GameKey.BUILD_BATTLE : original.game(),
                        changed == 7 ? original.capturedAt().plusNanos(1) : original.capturedAt());
                assertThrows(IllegalStateException.class, () -> journal.begin("test-provider", 1, foreign, REQUEST));
            }
        }
    }

    private static PlayerStateOperation operation(PlayerStateOperation.Kind kind, UUID operation, UUID connection) {
        return new PlayerStateOperation(kind, operation, uuid(2), uuid(3), uuid(4), uuid(5), uuid(6),
                uuid(7), connection, GameKey.ARENA, Instant.parse("2026-10-04T11:12:13.123456789Z"));
    }

    private static UUID uuid(long value) { return new UUID(0, value); }
}
