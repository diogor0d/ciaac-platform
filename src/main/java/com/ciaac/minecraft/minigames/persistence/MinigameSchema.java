package com.ciaac.minecraft.minigames.persistence;

import com.ciaac.minecraft.minigames.retention.RetentionSchemaMigration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

final class MinigameSchema {
    static final int VERSION = 6;

    private static final List<TableDefinition> TABLES = List.of(
            table("mg_snapshot", "snapshot_id", "operation_id", "session_id", "match_id", "player_id",
                    "game_id", "captured_at", "envelope", "checksum_sha256", "state", "updated_at"),
            table("mg_snapshot_transition", "snapshot_id", "sequence_number", "operation_id", "from_state",
                    "to_state", "occurred_at", "reason_code"),
            table("mg_session", "session_id", "match_id", "player_id", "game_id", "created_at", "updated_at",
                    "phase", "snapshot_id"),
            table("mg_session_transition", "session_id", "sequence_number", "operation_id", "from_phase",
                    "to_phase", "occurred_at", "reason_code"),
            table("mg_audit_event", "event_id", "operation_id", "occurred_at", "event_type", "subject_type",
                    "subject_id", "outcome_code", "detail_code"),
            table("mg_match_result", "result_id", "match_id", "game_id", "ruleset", "mode", "season",
                    "started_at", "finished_at", "outcome", "reason_code"),
            table("mg_player_result", "result_id", "player_id", "placement", "winner", "forfeit", "team_id"),
            table("mg_player_metric", "result_id", "player_id", "metric_key", "metric_value"),
            table("mg_announcement", "announcement_id", "match_id", "game_id", "kind", "destination",
                    "plain_text", "created_at", "state", "attempt_count", "last_code"),
            table("mg_staked_escrow", "escrow_id", "match_id", "ruleset_digest", "manifest_digest", "prepared_at",
                    "state", "result_id", "winner_id", "refund_reason", "quarantine_reason", "updated_at"),
            table("mg_staked_participant", "escrow_id", "player_id", "consented_at", "consent_match_id",
                    "consent_ruleset_digest", "consent_manifest_digest", "withdrawal_state",
                    "withdrawal_operation_id"),
            table("mg_staked_manifest_item", "escrow_id", "player_id", "item_id", "material", "amount",
                    "canonical_fingerprint"),
            table("mg_staked_payload", "escrow_id", "player_id", "manifest_digest", "storage_payload",
                    "armor_payload", "extra_payload", "storage_sha256", "armor_sha256", "extra_sha256",
                    "payload_sha256"),
            table("mg_staked_operation", "operation_id", "escrow_id", "player_id", "claim_id", "kind",
                    "request_digest", "from_state", "to_state", "accepted", "code", "created_at"),
            table("mg_staked_claim", "claim_id", "escrow_id", "source_player_id", "beneficiary_id", "result_id",
                    "kind", "payload_digest", "state", "begin_operation_id", "updated_at"),
            table("mg_retention_event", "event_id", "source_id", "player_id", "occurred_at", "local_date",
                    "kind", "season_id", "points", "anonymized_at"),
            table("mg_retention_projection", "player_id", "season_id", "current_join_streak",
                    "longest_join_streak", "last_join_date", "active_days", "weekly_objectives",
                    "season_milestones", "points", "join_freeze_available", "updated_at"),
            table("mg_retention_daily", "player_id", "local_date", "join_qualified", "active_qualified",
                    "sampled_minutes"),
            table("mg_retention_weekly", "player_id", "season_id", "week_starts_on"),
            table("mg_retention_entitlement", "player_id", "season_id", "reward_id", "state", "earned_at",
                    "updated_at", "operation_id", "detail_code"),
            table("mg_retention_selection", "player_id", "kind", "reward_id"),
            table("mg_retention_state", "key", "value"));

    private static final Set<String> INDEXES = Set.of(
            "mg_session_one_open_per_player",
            "mg_match_result_projection",
            "mg_player_metric_projection",
            "mg_announcement_waiting_cooldown",
            "mg_announcement_match_kind",
            "mg_staked_claim_pending",
            "mg_staked_operation_escrow",
            "mg_retention_event_player_date");

    private MinigameSchema() {}

    static void initialize(Connection connection) throws SQLException {
        int current;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            current = result.next() ? result.getInt(1) : 0;
        }
        if (current == RetentionSchemaMigration.FROM_VERSION) {
            migrateFromVersionFive(connection);
            return;
        }
        if (current != 0 && current != VERSION) {
            throw new SQLException("Unsupported SQLite database schema version " + current);
        }
        if (current == VERSION) {
            assertCurrentSchema(connection);
            return;
        }
        assertPristine(connection);
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_snapshot (
                        snapshot_id TEXT PRIMARY KEY,
                        operation_id TEXT NOT NULL,
                        session_id TEXT NOT NULL,
                        match_id TEXT NOT NULL,
                        player_id TEXT NOT NULL,
                        game_id TEXT NOT NULL,
                        captured_at TEXT NOT NULL,
                        envelope BLOB NOT NULL,
                        checksum_sha256 TEXT NOT NULL,
                        state TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_snapshot_transition (
                        snapshot_id TEXT NOT NULL,
                        sequence_number INTEGER NOT NULL,
                        operation_id TEXT NOT NULL,
                        from_state TEXT NOT NULL,
                        to_state TEXT NOT NULL,
                        occurred_at TEXT NOT NULL,
                        reason_code TEXT NOT NULL,
                        PRIMARY KEY (snapshot_id, operation_id),
                        UNIQUE (snapshot_id, sequence_number),
                        FOREIGN KEY (snapshot_id) REFERENCES mg_snapshot(snapshot_id) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_session (
                        session_id TEXT PRIMARY KEY,
                        match_id TEXT NOT NULL,
                        player_id TEXT NOT NULL,
                        game_id TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        phase TEXT NOT NULL,
                        snapshot_id TEXT NULL,
                        FOREIGN KEY (snapshot_id) REFERENCES mg_snapshot(snapshot_id) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX IF NOT EXISTS mg_session_one_open_per_player
                    ON mg_session(player_id)
                    WHERE phase NOT IN ('CLOSED', 'QUARANTINED')
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_session_transition (
                        session_id TEXT NOT NULL,
                        sequence_number INTEGER NOT NULL,
                        operation_id TEXT NOT NULL,
                        from_phase TEXT NOT NULL,
                        to_phase TEXT NOT NULL,
                        occurred_at TEXT NOT NULL,
                        reason_code TEXT NOT NULL,
                        PRIMARY KEY (session_id, operation_id),
                        UNIQUE (session_id, sequence_number),
                        FOREIGN KEY (session_id) REFERENCES mg_session(session_id) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_audit_event (
                        event_id TEXT PRIMARY KEY,
                        operation_id TEXT NOT NULL,
                        occurred_at TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        subject_type TEXT NOT NULL,
                        subject_id TEXT NOT NULL,
                        outcome_code TEXT NOT NULL,
                        detail_code TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_match_result (
                        result_id TEXT PRIMARY KEY,
                        match_id TEXT NOT NULL UNIQUE,
                        game_id TEXT NOT NULL,
                        ruleset TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        season TEXT NOT NULL,
                        started_at TEXT NOT NULL,
                        finished_at TEXT NOT NULL,
                        outcome TEXT NOT NULL,
                        reason_code TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS mg_match_result_projection
                    ON mg_match_result(game_id, ruleset, mode, season, finished_at)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_player_result (
                        result_id TEXT NOT NULL,
                        player_id TEXT NOT NULL,
                        placement INTEGER NOT NULL,
                        winner INTEGER NOT NULL,
                        forfeit INTEGER NOT NULL,
                        team_id TEXT NULL,
                        PRIMARY KEY (result_id, player_id),
                        FOREIGN KEY (result_id) REFERENCES mg_match_result(result_id) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_player_metric (
                        result_id TEXT NOT NULL,
                        player_id TEXT NOT NULL,
                        metric_key TEXT NOT NULL,
                        metric_value INTEGER NOT NULL,
                        PRIMARY KEY (result_id, player_id, metric_key),
                        FOREIGN KEY (result_id, player_id)
                            REFERENCES mg_player_result(result_id, player_id) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS mg_player_metric_projection
                    ON mg_player_metric(metric_key, player_id, metric_value)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_announcement (
                        announcement_id TEXT PRIMARY KEY,
                        match_id TEXT NOT NULL,
                        game_id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        destination TEXT NOT NULL,
                        plain_text TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        state TEXT NOT NULL,
                        attempt_count INTEGER NOT NULL,
                        last_code TEXT NOT NULL,
                        UNIQUE (match_id, kind, destination)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS mg_announcement_waiting_cooldown
                    ON mg_announcement(game_id, kind, destination, created_at)
                    """);
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX IF NOT EXISTS mg_announcement_match_kind
                    ON mg_announcement(match_id, kind, destination)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_staked_escrow (
                        escrow_id TEXT PRIMARY KEY,
                        match_id TEXT NOT NULL UNIQUE,
                        ruleset_digest TEXT NOT NULL CHECK(length(ruleset_digest) BETWEEN 1 AND 256),
                        manifest_digest TEXT NOT NULL CHECK(length(manifest_digest) BETWEEN 1 AND 256),
                        prepared_at TEXT NOT NULL,
                        state TEXT NOT NULL CHECK(state IN (
                            'PREPARED', 'CONSENTED', 'WITHDRAWING', 'WITHDRAWN', 'SETTLED',
                            'REFUNDING', 'DELIVERY_PENDING', 'DELIVERING', 'DELIVERED', 'QUARANTINED'
                        )),
                        result_id TEXT NULL,
                        winner_id TEXT NULL,
                        refund_reason TEXT NULL CHECK(refund_reason IS NULL OR length(refund_reason) BETWEEN 1 AND 64),
                        quarantine_reason TEXT NULL CHECK(quarantine_reason IS NULL OR length(quarantine_reason) BETWEEN 1 AND 128),
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_staked_participant (
                        escrow_id TEXT NOT NULL,
                        player_id TEXT NOT NULL,
                        consented_at TEXT NULL,
                        consent_match_id TEXT NULL,
                        consent_ruleset_digest TEXT NULL,
                        consent_manifest_digest TEXT NULL,
                        withdrawal_state TEXT NOT NULL CHECK(withdrawal_state IN (
                            'PENDING', 'WITHDRAWING', 'WITHDRAWN', 'QUARANTINED'
                        )),
                        withdrawal_operation_id TEXT NULL,
                        PRIMARY KEY (escrow_id, player_id),
                        FOREIGN KEY (escrow_id) REFERENCES mg_staked_escrow(escrow_id) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_staked_manifest_item (
                        escrow_id TEXT NOT NULL,
                        player_id TEXT NOT NULL,
                        item_id TEXT NOT NULL CHECK(length(item_id) BETWEEN 1 AND 128),
                        material TEXT NOT NULL CHECK(length(material) BETWEEN 1 AND 128),
                        amount INTEGER NOT NULL CHECK(amount BETWEEN 1 AND 2147483647),
                        canonical_fingerprint TEXT NOT NULL CHECK(length(canonical_fingerprint) BETWEEN 1 AND 256),
                        PRIMARY KEY (escrow_id, player_id, item_id),
                        FOREIGN KEY (escrow_id, player_id)
                            REFERENCES mg_staked_participant(escrow_id, player_id) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_staked_payload (
                        escrow_id TEXT NOT NULL,
                        player_id TEXT NOT NULL,
                        manifest_digest TEXT NOT NULL CHECK(length(manifest_digest) BETWEEN 1 AND 256),
                        storage_payload BLOB NOT NULL CHECK(length(storage_payload) <= 1048576),
                        armor_payload BLOB NOT NULL CHECK(length(armor_payload) <= 1048576),
                        extra_payload BLOB NOT NULL CHECK(length(extra_payload) <= 1048576),
                        storage_sha256 TEXT NOT NULL CHECK(length(storage_sha256) = 64),
                        armor_sha256 TEXT NOT NULL CHECK(length(armor_sha256) = 64),
                        extra_sha256 TEXT NOT NULL CHECK(length(extra_sha256) = 64),
                        payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256) = 64),
                        PRIMARY KEY (escrow_id, player_id),
                        CHECK(length(storage_payload) + length(armor_payload) + length(extra_payload) <= 3145728),
                        FOREIGN KEY (escrow_id, player_id)
                            REFERENCES mg_staked_participant(escrow_id, player_id) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_staked_operation (
                        operation_id TEXT PRIMARY KEY,
                        escrow_id TEXT NOT NULL,
                        player_id TEXT NULL,
                        claim_id TEXT NULL,
                        kind TEXT NOT NULL CHECK(length(kind) BETWEEN 1 AND 64),
                        request_digest TEXT NOT NULL CHECK(length(request_digest) = 64),
                        from_state TEXT NULL,
                        to_state TEXT NOT NULL CHECK(to_state IN (
                            'PREPARED', 'CONSENTED', 'WITHDRAWING', 'WITHDRAWN', 'SETTLED',
                            'REFUNDING', 'DELIVERY_PENDING', 'DELIVERING', 'DELIVERED', 'QUARANTINED'
                        )),
                        accepted INTEGER NOT NULL CHECK(accepted IN (0, 1)),
                        code TEXT NOT NULL CHECK(length(code) BETWEEN 1 AND 64),
                        created_at TEXT NOT NULL,
                        FOREIGN KEY (escrow_id) REFERENCES mg_staked_escrow(escrow_id) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mg_staked_claim (
                        claim_id TEXT PRIMARY KEY,
                        escrow_id TEXT NOT NULL,
                        source_player_id TEXT NOT NULL,
                        beneficiary_id TEXT NOT NULL,
                        result_id TEXT NOT NULL,
                        kind TEXT NOT NULL CHECK(kind IN ('SETTLEMENT', 'REFUND')),
                        payload_digest TEXT NOT NULL CHECK(length(payload_digest) = 64),
                        state TEXT NOT NULL CHECK(state IN (
                            'PENDING', 'DELIVERING', 'DELIVERED', 'QUARANTINED'
                        )),
                        begin_operation_id TEXT NULL,
                        updated_at TEXT NOT NULL,
                        UNIQUE (escrow_id, kind, source_player_id),
                        FOREIGN KEY (escrow_id, source_player_id)
                            REFERENCES mg_staked_payload(escrow_id, player_id) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS mg_staked_claim_pending
                    ON mg_staked_claim(escrow_id, state, updated_at)
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS mg_staked_operation_escrow
                    ON mg_staked_operation(escrow_id, created_at)
                    """);
            createRetentionSchema(statement);
            statement.execute("PRAGMA user_version = " + VERSION);
            assertCurrentSchema(connection);
            connection.commit();
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static void migrateFromVersionFive(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sql : RetentionSchemaMigration.statements()) statement.execute(sql);
            assertCurrentSchema(connection);
            connection.commit();
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static void createRetentionSchema(Statement statement) throws SQLException {
        for (String sql : RetentionSchemaMigration.statements()) {
            if (!sql.startsWith("PRAGMA user_version")) statement.execute(sql);
        }
    }

    private static void assertCurrentSchema(Connection connection) throws SQLException {
        int userVersion;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            if (!result.next()) {
                throw new SQLException("SQLite schema version is unavailable");
            }
            userVersion = result.getInt(1);
        }
        if (userVersion != VERSION) {
            throw new SQLException("SQLite schema version changed during initialization");
        }

        for (TableDefinition table : TABLES) {
            Set<String> columns = new java.util.HashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA table_info(" + table.name() + ")")) {
                while (result.next()) {
                    columns.add(result.getString("name"));
                }
            }
            if (!columns.containsAll(table.columns())) {
                throw new SQLException("SQLite table is incomplete: " + table.name());
            }
        }

        Set<String> indexes = new java.util.HashSet<>();
        for (TableDefinition table : TABLES) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA index_list(" + table.name() + ")")) {
                while (result.next()) {
                    indexes.add(result.getString("name"));
                }
            }
        }
        if (!indexes.containsAll(INDEXES)) {
            throw new SQLException("SQLite schema indexes are incomplete");
        }
    }

    private static void assertPristine(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT name
                     FROM sqlite_schema
                     WHERE name NOT LIKE 'sqlite_%'
                       AND type IN ('table', 'index', 'trigger', 'view')
                     LIMIT 1
                     """)) {
            if (result.next()) {
                throw new SQLException(
                        "Unversioned SQLite store already contains user-defined schema objects");
            }
        }
    }

    private static TableDefinition table(String name, String... columns) {
        return new TableDefinition(name, Set.of(columns));
    }

    private record TableDefinition(String name, Set<String> columns) {}
}
