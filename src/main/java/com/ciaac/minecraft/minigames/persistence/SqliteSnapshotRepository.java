package com.ciaac.minecraft.minigames.persistence;

import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.SnapshotRecord;
import com.ciaac.minecraft.minigames.isolation.SnapshotRepository;
import com.ciaac.minecraft.minigames.isolation.SnapshotState;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqliteSnapshotRepository implements SnapshotRepository {
    private final SqliteDatabase database;
    private final SnapshotEnvelopeCodec codec;

    public SqliteSnapshotRepository(SqliteDatabase database, SnapshotEnvelopeCodec codec) {
        this.database = Objects.requireNonNull(database, "database");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public boolean create(SnapshotRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] envelope = codec.encode(record.snapshot());
        String checksum = codec.checksum(envelope);
        if (!checksum.equals(record.checksumSha256())) {
            throw new IllegalStateException("Snapshot checksum does not match encoded payload");
        }
        return database.transaction(connection -> {
            ExistingSnapshot existing = findExisting(connection, record.snapshot().snapshotId());
            if (existing != null) {
                if (!existing.checksum().equals(checksum)) {
                    throw new PersistenceFailure("Conflicting snapshot identifier replay");
                }
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO mg_snapshot (
                        snapshot_id, operation_id, session_id, match_id, player_id, game_id,
                        captured_at, envelope, checksum_sha256, state, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                PlayerStateSnapshot snapshot = record.snapshot();
                statement.setString(1, snapshot.snapshotId().toString());
                statement.setString(2, snapshot.operationId().toString());
                statement.setString(3, snapshot.sessionId().toString());
                statement.setString(4, snapshot.matchId().toString());
                statement.setString(5, snapshot.playerId().toString());
                statement.setString(6, snapshot.game().id());
                statement.setString(7, snapshot.capturedAt().toString());
                statement.setBytes(8, envelope);
                statement.setString(9, checksum);
                statement.setString(10, record.state().name());
                statement.setString(11, record.updatedAt().toString());
                statement.executeUpdate();
            }
            return true;
        });
    }

    @Override
    public boolean transition(
            UUID snapshotId,
            UUID operationId,
            SnapshotState expected,
            SnapshotState target,
            Instant occurredAt,
            String reasonCode) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(occurredAt, "occurredAt");
        SnapshotRecord semanticGuard = find(snapshotId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown snapshot " + snapshotId));
        boolean changed = semanticGuard.transition(operationId, expected, target, occurredAt, reasonCode);
        if (!changed) {
            return false;
        }
        return database.transaction(connection -> {
            try (PreparedStatement replay = connection.prepareStatement("""
                    SELECT from_state, to_state, occurred_at, reason_code
                    FROM mg_snapshot_transition WHERE snapshot_id = ? AND operation_id = ?
                    """)) {
                replay.setString(1, snapshotId.toString());
                replay.setString(2, operationId.toString());
                try (ResultSet result = replay.executeQuery()) {
                    if (result.next()) {
                        boolean identical = result.getString(1).equals(expected.name())
                                && result.getString(2).equals(target.name())
                                && result.getString(3).equals(occurredAt.toString())
                                && result.getString(4).equals(
                                        com.ciaac.minecraft.minigames.runtime.MachineCode.normalize(
                                                reasonCode, "reasonCode"));
                        if (!identical) {
                            throw new PersistenceFailure("Conflicting snapshot transition replay");
                        }
                        return false;
                    }
                }
            }
            SnapshotState current;
            int nextSequence;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT state FROM mg_snapshot WHERE snapshot_id = ?")) {
                select.setString(1, snapshotId.toString());
                try (ResultSet result = select.executeQuery()) {
                    if (!result.next()) {
                        throw new PersistenceFailure("Snapshot disappeared during transition");
                    }
                    current = SnapshotState.valueOf(result.getString(1));
                }
            }
            if (current != expected) {
                throw new PersistenceFailure("Snapshot state changed concurrently");
            }
            try (PreparedStatement sequence = connection.prepareStatement(
                    "SELECT COUNT(*) FROM mg_snapshot_transition WHERE snapshot_id = ?")) {
                sequence.setString(1, snapshotId.toString());
                try (ResultSet result = sequence.executeQuery()) {
                    nextSequence = result.next() ? result.getInt(1) : 0;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO mg_snapshot_transition (
                        snapshot_id, sequence_number, operation_id, from_state, to_state,
                        occurred_at, reason_code
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, snapshotId.toString());
                insert.setInt(2, nextSequence);
                insert.setString(3, operationId.toString());
                insert.setString(4, expected.name());
                insert.setString(5, target.name());
                insert.setString(6, occurredAt.toString());
                insert.setString(7, com.ciaac.minecraft.minigames.runtime.MachineCode.normalize(
                        reasonCode, "reasonCode"));
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE mg_snapshot SET state = ?, updated_at = ?
                    WHERE snapshot_id = ? AND state = ?
                    """)) {
                update.setString(1, target.name());
                update.setString(2, occurredAt.toString());
                update.setString(3, snapshotId.toString());
                update.setString(4, expected.name());
                if (update.executeUpdate() != 1) {
                    throw new PersistenceFailure("Snapshot transition lost its expected-state race");
                }
            }
            return true;
        });
    }

    @Override
    public Optional<SnapshotRecord> find(UUID snapshotId) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        return database.read(connection -> Optional.ofNullable(load(connection, snapshotId)));
    }

    @Override
    public List<SnapshotRecord> nonTerminal() {
        return database.read(connection -> {
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT snapshot_id FROM mg_snapshot
                    WHERE state NOT IN ('RESTORED', 'QUARANTINED') ORDER BY captured_at, snapshot_id
                    """); ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(UUID.fromString(result.getString(1)));
                }
            }
            List<SnapshotRecord> records = new ArrayList<>();
            for (UUID id : ids) {
                records.add(load(connection, id));
            }
            return List.copyOf(records);
        });
    }

    private SnapshotRecord load(Connection connection, UUID snapshotId) throws SQLException {
        UUID storedSnapshotId;
        UUID storedOperationId;
        UUID storedSessionId;
        UUID storedMatchId;
        UUID storedPlayerId;
        String storedGameId;
        Instant storedCapturedAt;
        byte[] envelope;
        String checksum;
        SnapshotState storedState;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT snapshot_id, operation_id, session_id, match_id, player_id, game_id,
                       captured_at, envelope, checksum_sha256, state
                FROM mg_snapshot WHERE snapshot_id = ?
                """)) {
            statement.setString(1, snapshotId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                storedSnapshotId = UUID.fromString(result.getString(1));
                storedOperationId = UUID.fromString(result.getString(2));
                storedSessionId = UUID.fromString(result.getString(3));
                storedMatchId = UUID.fromString(result.getString(4));
                storedPlayerId = UUID.fromString(result.getString(5));
                storedGameId = result.getString(6);
                storedCapturedAt = Instant.parse(result.getString(7));
                envelope = result.getBytes(8);
                checksum = result.getString(9);
                storedState = SnapshotState.valueOf(result.getString(10));
            }
        }
        if (!codec.checksum(envelope).equals(checksum)) {
            throw new PersistenceFailure("Stored snapshot checksum verification failed");
        }
        PlayerStateSnapshot decoded = codec.decode(envelope);
        if (!decoded.snapshotId().equals(storedSnapshotId)
                || !decoded.operationId().equals(storedOperationId)
                || !decoded.sessionId().equals(storedSessionId)
                || !decoded.matchId().equals(storedMatchId)
                || !decoded.playerId().equals(storedPlayerId)
                || !decoded.game().id().equals(storedGameId)
                || !decoded.capturedAt().equals(storedCapturedAt)) {
            throw new PersistenceFailure("Stored snapshot metadata does not match its envelope");
        }
        SnapshotRecord record = new SnapshotRecord(decoded, checksum);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, from_state, to_state, occurred_at, reason_code
                FROM mg_snapshot_transition WHERE snapshot_id = ? ORDER BY sequence_number
                """)) {
            statement.setString(1, snapshotId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    record.transition(
                            UUID.fromString(result.getString(1)),
                            SnapshotState.valueOf(result.getString(2)),
                            SnapshotState.valueOf(result.getString(3)),
                            Instant.parse(result.getString(4)),
                            result.getString(5));
                }
            }
        }
        if (record.state() != storedState) {
            throw new PersistenceFailure("Snapshot state does not match its transition log");
        }
        return record;
    }

    private static ExistingSnapshot findExisting(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT checksum_sha256 FROM mg_snapshot WHERE snapshot_id = ?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new ExistingSnapshot(result.getString(1)) : null;
            }
        }
    }

    private record ExistingSnapshot(String checksum) {}
}
