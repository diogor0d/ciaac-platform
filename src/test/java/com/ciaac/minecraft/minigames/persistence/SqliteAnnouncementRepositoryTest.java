package com.ciaac.minecraft.minigames.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ciaac.minecraft.minigames.announcement.Announcement;
import com.ciaac.minecraft.minigames.announcement.AnnouncementDestination;
import com.ciaac.minecraft.minigames.announcement.AnnouncementKind;
import com.ciaac.minecraft.minigames.announcement.AnnouncementReservation;
import com.ciaac.minecraft.minigames.core.GameKey;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteAnnouncementRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reservedReplayIsUncertainAndNeverRetried() {
        Announcement announcement = announcement("Mensagem original.", Instant.EPOCH);
        try (SqliteDatabase database = database()) {
            SqliteAnnouncementRepository repository = new SqliteAnnouncementRepository(database);

            assertEquals(AnnouncementReservation.RESERVED, repository.reserve(
                    announcement, AnnouncementDestination.MINECRAFT, Duration.ZERO));
            assertEquals(AnnouncementReservation.DELIVERY_UNCERTAIN, repository.reserve(
                    announcement, AnnouncementDestination.MINECRAFT, Duration.ZERO));
        }
    }

    @Test
    void failedAndDeliveredReplaysRemainDistinct() {
        Announcement failed = announcement("Falhou.", Instant.EPOCH);
        Announcement delivered = announcement("Entregue.", Instant.EPOCH.plusSeconds(1));
        try (SqliteDatabase database = database()) {
            SqliteAnnouncementRepository repository = new SqliteAnnouncementRepository(database);

            assertEquals(AnnouncementReservation.RESERVED, repository.reserve(
                    failed, AnnouncementDestination.DISCORD, Duration.ZERO));
            repository.markFailed(failed.announcementId(), AnnouncementDestination.DISCORD, "PUBLISHER_EXCEPTION");
            assertEquals(AnnouncementReservation.FAILED_REPLAY, repository.reserve(
                    failed, AnnouncementDestination.DISCORD, Duration.ZERO));

            assertEquals(AnnouncementReservation.RESERVED, repository.reserve(
                    delivered, AnnouncementDestination.DISCORD, Duration.ZERO));
            repository.markDelivered(delivered.announcementId(), AnnouncementDestination.DISCORD, "SENT");
            assertEquals(AnnouncementReservation.IDENTICAL_REPLAY, repository.reserve(
                    delivered, AnnouncementDestination.DISCORD, Duration.ZERO));
        }
    }

    @Test
    void sameMatchPhaseDestinationWithDifferentPayloadIsRejected() {
        UUID matchId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Announcement original = new Announcement(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), matchId,
                GameKey.ARENA, AnnouncementKind.STARTING, "Mensagem original.", Instant.EPOCH);
        Announcement conflicting = new Announcement(
                UUID.fromString("33333333-3333-3333-3333-333333333333"), matchId,
                GameKey.ARENA, AnnouncementKind.STARTING, "Mensagem adulterada.", Instant.EPOCH);
        try (SqliteDatabase database = database()) {
            SqliteAnnouncementRepository repository = new SqliteAnnouncementRepository(database);
            repository.reserve(original, AnnouncementDestination.MINECRAFT, Duration.ZERO);

            assertThrows(PersistenceFailure.class, () -> repository.reserve(
                    conflicting, AnnouncementDestination.MINECRAFT, Duration.ZERO));
        }
    }

    private SqliteDatabase database() {
        return new SqliteDatabase(temporaryDirectory, Path.of("announcement.sqlite"));
    }

    private static Announcement announcement(String text, Instant createdAt) {
        return new Announcement(UUID.randomUUID(), UUID.randomUUID(), GameKey.ARENA,
                AnnouncementKind.STARTING, text, createdAt);
    }
}
