package com.ciaac.minecraft.minigames.announcement;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Announcement(
        UUID announcementId,
        UUID matchId,
        GameKey game,
        AnnouncementKind kind,
        String plainTextPtPt,
        Instant createdAt) {

    public Announcement {
        Objects.requireNonNull(announcementId, "announcementId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(createdAt, "createdAt");
        if (plainTextPtPt == null || plainTextPtPt.isBlank() || plainTextPtPt.length() > 500) {
            throw new IllegalArgumentException("Announcement text must contain 1-500 characters");
        }
    }

    /** Returns a deterministic delivery identity isolated to one destination. */
    public Announcement forDestination(AnnouncementDestination destination) {
        Objects.requireNonNull(destination, "destination");
        UUID deliveryId = UUID.nameUUIDFromBytes(
                ("ciaac-minigames:announcement:v1:" + announcementId + ":" + destination.name())
                        .getBytes(StandardCharsets.UTF_8));
        return new Announcement(deliveryId, matchId, game, kind, plainTextPtPt, createdAt);
    }
}
