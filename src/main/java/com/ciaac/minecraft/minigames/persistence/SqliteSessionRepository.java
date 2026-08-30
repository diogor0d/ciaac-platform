package com.ciaac.minecraft.minigames.persistence;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionTransition;
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

public final class SqliteSessionRepository implements SessionRepository {
    private final SqliteDatabase database;

    public SqliteSessionRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public boolean save(PlayerSession session) {
        Objects.requireNonNull(session, "session");
        return database.transaction(connection -> {
            StoredIdentity identity = findIdentity(connection, session.sessionId());
            boolean inserted = identity == null;
            if (identity == null) {
                insertSession(connection, session);
            } else {
                verifyIdentity(identity, session);
            }
            persistTransitions(connection, session);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE mg_session
                    SET updated_at = ?, phase = ?, snapshot_id = ?
                    WHERE session_id = ?
                    """)) {
                update.setString(1, session.updatedAt().toString());
                update.setString(2, session.phase().name());
                if (session.snapshotId().isPresent()) {
                    update.setString(3, session.snapshotId().orElseThrow().toString());
                } else {
                    update.setNull(3, java.sql.Types.VARCHAR);
                }
                update.setString(4, session.sessionId().toString());
                if (update.executeUpdate() != 1) {
                    throw new PersistenceFailure("Session disappeared during save");
                }
            }
            return inserted;
        });
    }

    @Override
    public Optional<PlayerSession> find(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return database.read(connection -> Optional.ofNullable(load(connection, sessionId)));
    }

    @Override
    public List<PlayerSession> nonTerminal() {
        return database.read(connection -> {
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT session_id FROM mg_session
                    WHERE phase <> 'CLOSED'
                    ORDER BY created_at, session_id
                    """); ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(UUID.fromString(result.getString(1)));
                }
            }
            List<PlayerSession> sessions = new ArrayList<>();
            for (UUID id : ids) {
                sessions.add(load(connection, id));
            }
            return List.copyOf(sessions);
        });
    }

    private static void insertSession(Connection connection, PlayerSession session) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO mg_session (
                    session_id, match_id, player_id, game_id, created_at,
                    updated_at, phase, snapshot_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
                """)) {
            insert.setString(1, session.sessionId().toString());
            insert.setString(2, session.matchId().toString());
            insert.setString(3, session.playerId().toString());
            insert.setString(4, session.game().id());
            insert.setString(5, session.createdAt().toString());
            insert.setString(6, session.updatedAt().toString());
            insert.setString(7, session.phase().name());
            insert.executeUpdate();
        }
    }

    private static void persistTransitions(Connection connection, PlayerSession session) throws SQLException {
        List<SessionTransition> transitions = session.transitions();
        for (int index = 0; index < transitions.size(); index++) {
            SessionTransition transition = transitions.get(index);
            try (PreparedStatement existing = connection.prepareStatement("""
                    SELECT sequence_number, from_phase, to_phase, occurred_at, reason_code
                    FROM mg_session_transition WHERE session_id = ? AND operation_id = ?
                    """)) {
                existing.setString(1, session.sessionId().toString());
                existing.setString(2, transition.operationId().toString());
                try (ResultSet result = existing.executeQuery()) {
                    if (result.next()) {
                        boolean identical = result.getInt(1) == index
                                && result.getString(2).equals(transition.from().name())
                                && result.getString(3).equals(transition.to().name())
                                && result.getString(4).equals(transition.occurredAt().toString())
                                && result.getString(5).equals(transition.reasonCode());
                        if (!identical) {
                            throw new PersistenceFailure("Conflicting session transition replay");
                        }
                        continue;
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO mg_session_transition (
                        session_id, sequence_number, operation_id, from_phase, to_phase,
                        occurred_at, reason_code
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, session.sessionId().toString());
                insert.setInt(2, index);
                insert.setString(3, transition.operationId().toString());
                insert.setString(4, transition.from().name());
                insert.setString(5, transition.to().name());
                insert.setString(6, transition.occurredAt().toString());
                insert.setString(7, transition.reasonCode());
                insert.executeUpdate();
            }
        }
    }

    private static PlayerSession load(Connection connection, UUID sessionId) throws SQLException {
        UUID matchId;
        UUID playerId;
        GameKey game;
        Instant createdAt;
        SessionPhase storedPhase;
        UUID snapshotId;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT match_id, player_id, game_id, created_at, phase, snapshot_id
                FROM mg_session WHERE session_id = ?
                """)) {
            statement.setString(1, sessionId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                matchId = UUID.fromString(result.getString(1));
                playerId = UUID.fromString(result.getString(2));
                game = GameKey.fromId(result.getString(3))
                        .orElseThrow(() -> new PersistenceFailure("Stored session has an unknown game"));
                createdAt = Instant.parse(result.getString(4));
                storedPhase = SessionPhase.valueOf(result.getString(5));
                String rawSnapshotId = result.getString(6);
                snapshotId = rawSnapshotId == null ? null : UUID.fromString(rawSnapshotId);
            }
        }
        PlayerSession session = new PlayerSession(sessionId, matchId, playerId, game, createdAt);
        boolean snapshotBound = false;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, from_phase, to_phase, occurred_at, reason_code
                FROM mg_session_transition WHERE session_id = ? ORDER BY sequence_number
                """)) {
            statement.setString(1, sessionId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SessionPhase from = SessionPhase.valueOf(result.getString(2));
                    SessionPhase to = SessionPhase.valueOf(result.getString(3));
                    if (!snapshotBound && snapshotId != null
                            && from == SessionPhase.SNAPSHOTTING
                            && to == SessionPhase.SNAPSHOT_COMMITTED) {
                        session.bindSnapshot(snapshotId);
                        snapshotBound = true;
                    }
                    session.transition(
                            UUID.fromString(result.getString(1)),
                            from,
                            to,
                            Instant.parse(result.getString(4)),
                            result.getString(5));
                }
            }
        }
        if (!snapshotBound && snapshotId != null && session.phase() == SessionPhase.SNAPSHOTTING) {
            session.bindSnapshot(snapshotId);
            snapshotBound = true;
        }
        if (snapshotId != null && !snapshotBound) {
            throw new PersistenceFailure("Stored session snapshot is inconsistent with its phase history");
        }
        if (session.phase() != storedPhase) {
            throw new PersistenceFailure("Session phase does not match its transition log");
        }
        return session;
    }

    private static StoredIdentity findIdentity(Connection connection, UUID sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT match_id, player_id, game_id, created_at
                FROM mg_session WHERE session_id = ?
                """)) {
            statement.setString(1, sessionId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new StoredIdentity(
                                UUID.fromString(result.getString(1)),
                                UUID.fromString(result.getString(2)),
                                result.getString(3),
                                Instant.parse(result.getString(4)))
                        : null;
            }
        }
    }

    private static void verifyIdentity(StoredIdentity stored, PlayerSession session) {
        if (!stored.matchId().equals(session.matchId())
                || !stored.playerId().equals(session.playerId())
                || !stored.gameId().equals(session.game().id())
                || !stored.createdAt().equals(session.createdAt())) {
            throw new PersistenceFailure("Conflicting immutable session replay");
        }
    }

    private record StoredIdentity(UUID matchId, UUID playerId, String gameId, Instant createdAt) {}
}
