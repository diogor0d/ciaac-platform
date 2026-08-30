package com.ciaac.minecraft.minigames.announcement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AnnouncementServiceTest {
    @Test
    void replaysDoNotCallPublisherAndOnlyDeliveredReplayIsSuccessful() {
        for (AnnouncementReservation reservation : new AnnouncementReservation[] {
                AnnouncementReservation.IDENTICAL_REPLAY,
                AnnouncementReservation.FAILED_REPLAY,
                AnnouncementReservation.DELIVERY_UNCERTAIN
        }) {
            var publisherCalls = new int[] {0};
            AnnouncementRepository repository = fixedReservation(reservation);
            AnnouncementPublisher publisher = publisher(publisherCalls);

            AnnouncementPublishResult result = new AnnouncementService(
                    repository, publisher, Duration.ZERO).publish(announcement());

            assertEquals(reservation == AnnouncementReservation.IDENTICAL_REPLAY,
                    result.delivered(), reservation.name());
            assertEquals(reservation == AnnouncementReservation.IDENTICAL_REPLAY
                    ? "IDENTICAL_REPLAY" : reservation.name(), result.code());
            assertEquals(0, publisherCalls[0], reservation.name());
        }
    }

    @Test
    void reservationFailureIsReportedWithoutCallingPublisher() {
        var publisherCalls = new int[] {0};
        AnnouncementRepository repository = new AnnouncementRepository() {
            @Override
            public AnnouncementReservation reserve(
                    Announcement announcement,
                    AnnouncementDestination destination,
                    Duration waitingCooldown) {
                throw new IllegalStateException("storage unavailable");
            }

            @Override
            public void markDelivered(UUID announcementId, AnnouncementDestination destination, String code) {
            }

            @Override
            public void markFailed(UUID announcementId, AnnouncementDestination destination, String code) {
            }
        };
        AnnouncementPublisher publisher = publisher(publisherCalls);

        AnnouncementPublishResult result = new AnnouncementService(
                repository, publisher, Duration.ZERO).publish(announcement());

        assertFalse(result.delivered());
        assertEquals("RESERVATION_FAILURE", result.code());
        assertEquals(0, publisherCalls[0]);
    }

    private static Announcement announcement() {
        return new Announcement(
                UUID.randomUUID(), UUID.randomUUID(), GameKey.ARENA,
                AnnouncementKind.STARTING, "Mensagem segura.", Instant.EPOCH);
    }

    private static AnnouncementRepository fixedReservation(AnnouncementReservation reservation) {
        return new AnnouncementRepository() {
            @Override
            public AnnouncementReservation reserve(
                    Announcement announcement,
                    AnnouncementDestination destination,
                    Duration waitingCooldown) {
                return reservation;
            }

            @Override
            public void markDelivered(UUID announcementId, AnnouncementDestination destination, String code) {
            }

            @Override
            public void markFailed(UUID announcementId, AnnouncementDestination destination, String code) {
            }
        };
    }

    private static AnnouncementPublisher publisher(int[] calls) {
        return new AnnouncementPublisher() {
            @Override
            public AnnouncementDestination destination() {
                return AnnouncementDestination.MINECRAFT;
            }

            @Override
            public AnnouncementPublishResult publish(Announcement announcement) {
                calls[0]++;
                return AnnouncementPublishResult.delivered("SENT");
            }
        };
    }
}
