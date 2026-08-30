package com.ciaac.minecraft.minigames.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;

public final class SqliteAuditRepository implements AuditRepository {
    private final SqliteDatabase database;

    public SqliteAuditRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public boolean append(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        return database.transaction(connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT operation_id, occurred_at, event_type, subject_type,
                           subject_id, outcome_code, detail_code
                    FROM mg_audit_event WHERE event_id = ?
                    """)) {
                select.setString(1, event.eventId().toString());
                try (ResultSet result = select.executeQuery()) {
                    if (result.next()) {
                        boolean identical = result.getString(1).equals(event.operationId().toString())
                                && result.getString(2).equals(event.occurredAt().toString())
                                && result.getString(3).equals(event.eventType())
                                && result.getString(4).equals(event.subjectType())
                                && result.getString(5).equals(event.subjectId())
                                && result.getString(6).equals(event.outcomeCode())
                                && result.getString(7).equals(event.detailCode());
                        if (!identical) {
                            throw new PersistenceFailure("Conflicting audit event replay");
                        }
                        return false;
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO mg_audit_event (
                        event_id, operation_id, occurred_at, event_type, subject_type,
                        subject_id, outcome_code, detail_code
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, event.eventId().toString());
                insert.setString(2, event.operationId().toString());
                insert.setString(3, event.occurredAt().toString());
                insert.setString(4, event.eventType());
                insert.setString(5, event.subjectType());
                insert.setString(6, event.subjectId());
                insert.setString(7, event.outcomeCode());
                insert.setString(8, event.detailCode());
                insert.executeUpdate();
            }
            return true;
        });
    }
}
