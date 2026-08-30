package com.ciaac.minecraft.minigames.announcement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AnnouncementIdentityTest {
    @Test
    void deliveryIdentityIsDeterministicAndBoundToDestination() {
        Announcement logical = new Announcement(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                GameKey.ARENA,
                AnnouncementKind.STARTING,
                "A arena vai começar!",
                Instant.parse("2026-08-24T12:00:00Z"));

        Announcement minecraft = logical.forDestination(AnnouncementDestination.MINECRAFT);
        Announcement discord = logical.forDestination(AnnouncementDestination.DISCORD);

        assertNotEquals(minecraft.announcementId(), discord.announcementId());
        assertEquals(minecraft, logical.forDestination(AnnouncementDestination.MINECRAFT));
        assertEquals(discord, logical.forDestination(AnnouncementDestination.DISCORD));
        assertEquals(logical.matchId(), minecraft.matchId());
        assertEquals(logical.plainTextPtPt(), discord.plainTextPtPt());
    }

    @Test
    void dispatcherPublishesIndependentDestinationRecords() {
        CapturingPublisher minecraft = new CapturingPublisher(AnnouncementDestination.MINECRAFT);
        CapturingPublisher discord = new CapturingPublisher(AnnouncementDestination.DISCORD);
        AnnouncementDispatcher dispatcher = new AnnouncementDispatcher(
                service(minecraft), Optional.of(service(discord)), true, false,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), ignored -> "Jogador");

        dispatcher.starting(GameKey.ARENA, UUID.fromString("44444444-4444-4444-4444-444444444444"));

        assertNotEquals(minecraft.captured.announcementId(), discord.captured.announcementId());
        assertEquals(minecraft.captured.matchId(), discord.captured.matchId());
        assertEquals(minecraft.captured.plainTextPtPt(), discord.captured.plainTextPtPt());
    }

    private static AnnouncementService service(AnnouncementPublisher publisher) {
        return new AnnouncementService(new AnnouncementRepository() {
            @Override
            public AnnouncementReservation reserve(
                    Announcement announcement,
                    AnnouncementDestination destination,
                    Duration waitingCooldown) {
                return AnnouncementReservation.RESERVED;
            }

            @Override
            public void markDelivered(UUID announcementId, AnnouncementDestination destination, String code) {
            }

            @Override
            public void markFailed(UUID announcementId, AnnouncementDestination destination, String code) {
            }
        }, publisher, Duration.ZERO);
    }

    private static final class CapturingPublisher implements AnnouncementPublisher {
        private final AnnouncementDestination destination;
        private Announcement captured;

        private CapturingPublisher(AnnouncementDestination destination) {
            this.destination = destination;
        }

        @Override
        public AnnouncementDestination destination() {
            return destination;
        }

        @Override
        public AnnouncementPublishResult publish(Announcement announcement) {
            captured = announcement;
            return AnnouncementPublishResult.delivered("SENT");
        }
    }
}
