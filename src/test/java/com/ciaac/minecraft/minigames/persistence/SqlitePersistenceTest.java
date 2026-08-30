package com.ciaac.minecraft.minigames.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.SnapshotRecord;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlitePersistenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAndReopensOnlyTheCurrentSchema() {
        Path database = temporaryDirectory.resolve("minigames.sqlite");
        assertDoesNotThrow(() -> {
            try (SqliteDatabase ignored = new SqliteDatabase(temporaryDirectory, Path.of("minigames.sqlite"))) {
                // Schema creation and assertions run in the constructor.
            }
            try (SqliteDatabase ignored = new SqliteDatabase(temporaryDirectory, Path.of("minigames.sqlite"))) {
                // Existing current schema is asserted before exposure.
            }
        });
        assertEquals(true, database.toFile().isFile());
    }

    @Test
    void migratesVersionFiveTransactionallyAndRejectsOtherVersions() throws Exception {
        Path legacy = temporaryDirectory.resolve("legacy.sqlite");
        createVersionFiveFixture(legacy);
        assertDoesNotThrow(() -> new SqliteDatabase(temporaryDirectory, Path.of("legacy.sqlite")).close());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + legacy);
             var result = connection.createStatement().executeQuery(
                     "SELECT detail_code FROM mg_audit_event WHERE event_id = 'fixture'")) {
            assertEquals(true, result.next());
            assertEquals("PRESERVED", result.getString(1));
        }

        Path unsupported = temporaryDirectory.resolve("unsupported.sqlite");
        createDatabaseWithVersion(unsupported, 4);
        assertThrows(PersistenceFailure.class,
                () -> new SqliteDatabase(temporaryDirectory, Path.of("unsupported.sqlite")));

        Path future = temporaryDirectory.resolve("future.sqlite");
        createDatabaseWithVersion(future, MinigameSchema.VERSION + 1);
        assertThrows(PersistenceFailure.class,
                () -> new SqliteDatabase(temporaryDirectory, Path.of("future.sqlite")));
    }

    private void createVersionFiveFixture(Path path) throws Exception {
        try (SqliteDatabase ignored = new SqliteDatabase(temporaryDirectory, path.getFileName())) {
            // Start from the exact v6 structure, then remove only the v6 module to obtain a faithful v5 fixture.
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO mg_audit_event VALUES "
                    + "('fixture','operation','2026-08-27T00:00:00Z','TEST','FIXTURE','fixture','OK','PRESERVED')");
            statement.execute("DROP INDEX mg_retention_event_player_date");
            statement.execute("DROP TABLE mg_retention_entitlement");
            statement.execute("DROP TABLE mg_retention_selection");
            statement.execute("DROP TABLE mg_retention_state");
            statement.execute("DROP TABLE mg_retention_weekly");
            statement.execute("DROP TABLE mg_retention_daily");
            statement.execute("DROP TABLE mg_retention_projection");
            statement.execute("DROP TABLE mg_retention_event");
            statement.execute("PRAGMA user_version = 5");
        }
    }

    @Test
    void rejectsCurrentSchemaWithMissingRequiredIndex() {
        Path database = temporaryDirectory.resolve("missing-index.sqlite");
        try (SqliteDatabase ignored = new SqliteDatabase(temporaryDirectory, Path.of("missing-index.sqlite"))) {
            // Create the pristine schema first.
        }
        execute(database, "DROP INDEX mg_match_result_projection");
        assertThrows(PersistenceFailure.class,
                () -> new SqliteDatabase(temporaryDirectory, Path.of("missing-index.sqlite")));
    }

    @Test
    void rejectsUnversionedStoreContainingMinigameObjects() {
        Path database = temporaryDirectory.resolve("unversioned.sqlite");
        execute(database, "CREATE TABLE mg_snapshot (snapshot_id TEXT PRIMARY KEY)");

        assertThrows(PersistenceFailure.class,
                () -> new SqliteDatabase(temporaryDirectory, Path.of("unversioned.sqlite")));
    }

    @Test
    void rejectsUnversionedStoreContainingUnrelatedUserObjects() {
        Path database = temporaryDirectory.resolve("unrelated.sqlite");
        execute(database, "CREATE TABLE unrelated_data (value TEXT NOT NULL)");

        assertThrows(PersistenceFailure.class,
                () -> new SqliteDatabase(temporaryDirectory, Path.of("unrelated.sqlite")));
    }

    @Test
    void rejectsSnapshotWhenRowMetadataDiffersFromEnvelope() {
        Path databasePath = temporaryDirectory.resolve("snapshot.sqlite");
        SnapshotEnvelopeCodec codec = new SnapshotEnvelopeCodec();
        SnapshotRecord record = snapshot(codec);
        try (SqliteDatabase database = new SqliteDatabase(temporaryDirectory, Path.of("snapshot.sqlite"))) {
            SqliteSnapshotRepository repository = new SqliteSnapshotRepository(database, codec);
            assertEquals(true, repository.create(record));
            database.transaction(connection -> {
                try (var update = connection.prepareStatement(
                        "UPDATE mg_snapshot SET game_id = ? WHERE snapshot_id = ?")) {
                    update.setString(1, GameKey.ARENA.id());
                    update.setString(2, record.snapshot().snapshotId().toString());
                    update.executeUpdate();
                }
                return null;
            });
            assertThrows(PersistenceFailure.class, () -> repository.find(record.snapshot().snapshotId()));
        }
        assertEquals(true, databasePath.toFile().isFile());
    }

    private static SnapshotRecord snapshot(SnapshotEnvelopeCodec codec) {
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                GameKey.BUILD_BATTLE,
                Instant.parse("2026-08-24T12:00:00Z"),
                Map.of(PlayerStateFacet.INVENTORY, new byte[] {1, 2, 3}));
        return new SnapshotRecord(snapshot, codec.checksum(codec.encode(snapshot)));
    }

    private static void createDatabaseWithVersion(Path path, int version) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + version);
        }
    }

    private static void execute(Path path, String sql) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new AssertionError(failure);
        }
    }
}
