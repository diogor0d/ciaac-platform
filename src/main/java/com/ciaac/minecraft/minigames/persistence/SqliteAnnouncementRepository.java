package com.ciaac.minecraft.minigames.persistence;

import com.ciaac.minecraft.minigames.announcement.Announcement;
import com.ciaac.minecraft.minigames.announcement.AnnouncementDeliveryState;
import com.ciaac.minecraft.minigames.announcement.AnnouncementDestination;
import com.ciaac.minecraft.minigames.announcement.AnnouncementKind;
import com.ciaac.minecraft.minigames.announcement.AnnouncementRepository;
import com.ciaac.minecraft.minigames.announcement.AnnouncementReservation;
import com.ciaac.minecraft.minigames.runtime.MachineCode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class SqliteAnnouncementRepository implements AnnouncementRepository {
    private final SqliteDatabase database;

    public SqliteAnnouncementRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public AnnouncementReservation reserve(
            Announcement announcement,
            AnnouncementDestination destination,
            Duration waitingCooldown) {
        Objects.requireNonNull(announcement, "announcement");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(waitingCooldown, "waitingCooldown");
        return database.transaction(connection -> {
            try (PreparedStatement matchKind = connection.prepareStatement("""
                    SELECT match_id, game_id, kind, plain_text, created_at, state FROM mg_announcement
                    WHERE match_id = ? AND kind = ? AND destination = ?
                    """)) {
                matchKind.setString(1, announcement.matchId().toString());
                matchKind.setString(2, announcement.kind().name());
                matchKind.setString(3, destination.name());
                try (ResultSet result = matchKind.executeQuery()) {
                    if (result.next()) {
                        verifyPayload(result, announcement);
                        return reservationForState(result.getString("state"));
                    }
                }
            }
            try (PreparedStatement existing = connection.prepareStatement("""
                    SELECT match_id, game_id, kind, plain_text, created_at, state
                    FROM mg_announcement
                    WHERE announcement_id = ? AND destination = ?
                    """)) {
                existing.setString(1, announcement.announcementId().toString());
                existing.setString(2, destination.name());
                try (ResultSet result = existing.executeQuery()) {
                    if (result.next()) {
                        verifyPayload(result, announcement);
                        return reservationForState(result.getString("state"));
                    }
                }
            }
            if (announcement.kind() == AnnouncementKind.WAITING_FOR_PLAYERS) {
                try (PreparedStatement previous = connection.prepareStatement("""
                        SELECT created_at FROM mg_announcement
                        WHERE game_id = ? AND kind = ? AND destination = ?
                        ORDER BY created_at DESC LIMIT 1
                        """)) {
                    previous.setString(1, announcement.game().id());
                    previous.setString(2, announcement.kind().name());
                    previous.setString(3, destination.name());
                    try (ResultSet result = previous.executeQuery()) {
                        if (result.next()) {
                            Instant last = Instant.parse(result.getString(1));
                            if (announcement.createdAt().isBefore(last.plus(waitingCooldown))) {
                                return AnnouncementReservation.COOLDOWN_REJECTED;
                            }
                        }
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO mg_announcement (
                        announcement_id, match_id, game_id, kind, destination,
                        plain_text, created_at, state, attempt_count, last_code
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                    """)) {
                insert.setString(1, announcement.announcementId().toString());
                insert.setString(2, announcement.matchId().toString());
                insert.setString(3, announcement.game().id());
                insert.setString(4, announcement.kind().name());
                insert.setString(5, destination.name());
                insert.setString(6, announcement.plainTextPtPt());
                insert.setString(7, announcement.createdAt().toString());
                insert.setString(8, AnnouncementDeliveryState.RESERVED.name());
                insert.setString(9, "RESERVED");
                insert.executeUpdate();
            }
            return AnnouncementReservation.RESERVED;
        });
    }

    private static void verifyPayload(ResultSet result, Announcement announcement) throws java.sql.SQLException {
        boolean identical = result.getString("match_id").equals(announcement.matchId().toString())
                && result.getString("game_id").equals(announcement.game().id())
                && result.getString("kind").equals(announcement.kind().name())
                && result.getString("plain_text").equals(announcement.plainTextPtPt())
                && result.getString("created_at").equals(announcement.createdAt().toString());
        if (!identical) throw new PersistenceFailure("Conflicting announcement payload replay");
    }

    private static AnnouncementReservation reservationForState(String rawState) {
        final AnnouncementDeliveryState state;
        try {
            state = AnnouncementDeliveryState.valueOf(rawState);
        } catch (RuntimeException exception) {
            throw new PersistenceFailure("Unknown announcement delivery state: " + rawState);
        }
        return switch (state) {
            case DELIVERED -> AnnouncementReservation.IDENTICAL_REPLAY;
            case FAILED -> AnnouncementReservation.FAILED_REPLAY;
            case RESERVED -> AnnouncementReservation.DELIVERY_UNCERTAIN;
        };
    }

    @Override
    public void markDelivered(UUID announcementId, AnnouncementDestination destination, String code) {
        update(announcementId, destination, AnnouncementDeliveryState.DELIVERED, code);
    }

    @Override
    public void markFailed(UUID announcementId, AnnouncementDestination destination, String code) {
        update(announcementId, destination, AnnouncementDeliveryState.FAILED, code);
    }

    private void update(
            UUID announcementId,
            AnnouncementDestination destination,
            AnnouncementDeliveryState state,
            String code) {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE mg_announcement
                    SET state = ?, attempt_count = attempt_count + 1, last_code = ?
                    WHERE announcement_id = ? AND destination = ?
                    """)) {
                statement.setString(1, state.name());
                statement.setString(2, MachineCode.normalize(code, "code"));
                statement.setString(3, Objects.requireNonNull(announcementId, "announcementId").toString());
                statement.setString(4, Objects.requireNonNull(destination, "destination").name());
                if (statement.executeUpdate() != 1) {
                    throw new PersistenceFailure("Announcement delivery has no reservation");
                }
            }
            return null;
        });
    }
}
