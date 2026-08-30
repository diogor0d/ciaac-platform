package com.ciaac.minecraft.minigames.announcement;

public interface AnnouncementPublisher {
    AnnouncementDestination destination();

    AnnouncementPublishResult publish(Announcement announcement);
}
