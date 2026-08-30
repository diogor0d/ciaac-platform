package com.ciaac.minecraft.minigames.announcement;

import java.time.Duration;
import java.util.UUID;

public interface AnnouncementRepository {
    AnnouncementReservation reserve(
            Announcement announcement,
            AnnouncementDestination destination,
            Duration waitingCooldown);

    void markDelivered(UUID announcementId, AnnouncementDestination destination, String code);

    void markFailed(UUID announcementId, AnnouncementDestination destination, String code);
}
