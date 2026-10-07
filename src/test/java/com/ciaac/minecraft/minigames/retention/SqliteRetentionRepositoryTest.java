package com.ciaac.minecraft.minigames.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteRetentionRepositoryTest {
    @TempDir Path temporaryDirectory;

    @Test void persistsIdempotentCreditsAndRebuildsMutableStateAfterRestart() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID connection = UUID.fromString("00000000-0000-0000-0000-000000000302");
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        try (SqliteDatabase database = new SqliteDatabase(temporaryDirectory, Path.of("platform.sqlite"))) {
            PassportService service = service(new SqliteRetentionRepository(database));
            service.qualifyJoin(player, connection, now.minus(Duration.ofMinutes(10)), now);
            assertEquals(1, service.passport(player, now).points());
        }
        try (SqliteDatabase database = new SqliteDatabase(temporaryDirectory, Path.of("platform.sqlite"))) {
            PassportService service = service(new SqliteRetentionRepository(database));
            assertEquals(1, service.passport(player, now).points());
            service.qualifyJoin(player, connection, now.minus(Duration.ofMinutes(10)), now);
            assertEquals(1, service.passport(player, now).points());
        }
    }

    @Test void failedWorkAndFailedCommitLeaveCacheUntouchedAndAllowRetry() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000311");
        UUID connection = UUID.fromString("00000000-0000-0000-0000-000000000312");
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        try (SqliteDatabase database = new SqliteDatabase(temporaryDirectory, Path.of("rollback.sqlite"))) {
            SqliteRetentionRepository repository = new SqliteRetentionRepository(database);
            assertThrows(IllegalStateException.class, () -> repository.transaction(player, ledger -> {
                ledger.select("titulo", "uncommitted");
                throw new IllegalStateException("failed work");
            }));
            assertTrue(repository.findLedger(player).isEmpty());

            database.transaction(connectionHandle -> {
                try (var statement = connectionHandle.createStatement()) {
                    statement.execute("CREATE TRIGGER reject_retention_event BEFORE INSERT ON mg_retention_event "
                            + "BEGIN SELECT RAISE(ABORT, 'forced failure'); END");
                }
                return null;
            });
            PassportService service = service(repository);
            assertThrows(RuntimeException.class,
                    () -> service.qualifyJoin(player, connection, now.minus(Duration.ofMinutes(10)), now));
            assertTrue(repository.findLedger(player).isEmpty());

            database.transaction(connectionHandle -> {
                try (var statement = connectionHandle.createStatement()) {
                    statement.execute("DROP TRIGGER reject_retention_event");
                }
                return null;
            });
            assertEquals(PassportService.Outcome.CREDITED,
                    service.qualifyJoin(player, connection, now.minus(Duration.ofMinutes(10)), now).outcome());
            assertEquals(1, service.passport(player, now).points());
        }
    }

    @Test void repeatedSnapshotsAndPlaceholderQueriesPerformNoWrites() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000321");
        try (SqliteDatabase database = new SqliteDatabase(temporaryDirectory, Path.of("readonly.sqlite"))) {
            SqliteRetentionRepository repository = new SqliteRetentionRepository(database);
            PassportService service = service(repository);
            PassportPlaceholderValues placeholders = new PassportPlaceholderValues(service);
            Instant now = Instant.parse("2026-09-15T12:00:00Z");
            long before = totalChanges(database);
            for (int index = 0; index < 5; index++) {
                assertEquals(0, service.passport(player, now).points());
                assertEquals("0", placeholders.value(player, "points", now).orElseThrow());
                assertEquals("0", placeholders.value(player, "rank_points", now).orElseThrow());
                assertTrue(placeholders.value(player, "unknown", now).isEmpty());
            }
            assertEquals(before, totalChanges(database));
            assertTrue(repository.findLedger(player).isEmpty());
        }
    }

    private static long totalChanges(SqliteDatabase database) {
        return database.read(connection -> {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT total_changes()")) {
                return rows.next() ? rows.getLong(1) : -1L;
            }
        });
    }

    private static PassportService service(RetentionRepository repository) {
        RetentionConfiguration configuration = new RetentionConfiguration(true, LisbonSeasonCalendar.LISBON,
                LisbonSeasonCalendar.ANCHOR, 3, 14, Duration.ofMinutes(10), 15, 3, 12,
                false, false, false, List.of());
        return new PassportService(configuration, repository, List.of(), List.of());
    }
}
