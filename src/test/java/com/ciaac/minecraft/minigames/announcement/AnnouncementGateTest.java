package com.ciaac.minecraft.minigames.announcement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AnnouncementGateTest {
    @Test
    void deduplicatesByAnnouncementAndMatchPhase() {
        AnnouncementGate gate = new AnnouncementGate(Duration.ofMinutes(15));
        UUID match = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-22T12:00:00Z");
        Announcement first = announcement(match, AnnouncementKind.STARTING, now);

        assertTrue(gate.accept(first));
        assertFalse(gate.accept(first));
        assertFalse(gate.accept(announcement(match, AnnouncementKind.STARTING, now.plusSeconds(1))));
        assertTrue(gate.accept(announcement(match, AnnouncementKind.WINNER, now.plusSeconds(2))));
    }

    @Test
    void throttlesWaitingAnnouncementsAcrossMatchesOfTheSameGame() {
        AnnouncementGate gate = new AnnouncementGate(Duration.ofMinutes(15));
        Instant now = Instant.parse("2026-08-22T12:00:00Z");

        assertTrue(gate.accept(announcement(UUID.randomUUID(), AnnouncementKind.WAITING_FOR_PLAYERS, now)));
        assertFalse(gate.accept(announcement(
                UUID.randomUUID(), AnnouncementKind.WAITING_FOR_PLAYERS, now.plus(Duration.ofMinutes(14)))));
        assertTrue(gate.accept(announcement(
                UUID.randomUUID(), AnnouncementKind.WAITING_FOR_PLAYERS, now.plus(Duration.ofMinutes(15)))));
    }

    private static Announcement announcement(UUID match, AnnouncementKind kind, Instant at) {
        return new Announcement(
                UUID.randomUUID(), match, GameKey.ARENA, kind, "Mensagem segura.", at);
    }
}
