package com.ciaac.minecraft.minigames.announcement;

import java.time.Duration;
import java.util.Objects;

/** Persist-before-send announcement delivery with restart-safe deduplication. */
public final class AnnouncementService {
    private final AnnouncementRepository repository;
    private final AnnouncementPublisher publisher;
    private final Duration waitingCooldown;

    public AnnouncementService(
            AnnouncementRepository repository,
            AnnouncementPublisher publisher,
            Duration waitingCooldown) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.waitingCooldown = Objects.requireNonNull(waitingCooldown, "waitingCooldown");
        if (waitingCooldown.isNegative()) {
            throw new IllegalArgumentException("waitingCooldown must not be negative");
        }
    }

    public AnnouncementPublishResult publish(Announcement announcement) {
        Objects.requireNonNull(announcement, "announcement");
        AnnouncementReservation reservation;
        try {
            reservation = repository.reserve(
                    announcement, publisher.destination(), waitingCooldown);
        } catch (RuntimeException exception) {
            return AnnouncementPublishResult.failed("RESERVATION_FAILURE");
        }
        if (reservation == AnnouncementReservation.IDENTICAL_REPLAY) {
            return AnnouncementPublishResult.delivered("IDENTICAL_REPLAY");
        }
        if (reservation == AnnouncementReservation.FAILED_REPLAY) {
            return AnnouncementPublishResult.failed("FAILED_REPLAY");
        }
        if (reservation == AnnouncementReservation.DELIVERY_UNCERTAIN) {
            return AnnouncementPublishResult.failed("DELIVERY_UNCERTAIN");
        }
        if (reservation == AnnouncementReservation.COOLDOWN_REJECTED) {
            return AnnouncementPublishResult.failed("WAITING_COOLDOWN");
        }
        AnnouncementPublishResult result;
        try {
            result = publisher.publish(announcement);
        } catch (RuntimeException exception) {
            result = AnnouncementPublishResult.failed("PUBLISHER_EXCEPTION");
        }
        try {
            if (result.delivered()) {
                repository.markDelivered(announcement.announcementId(), publisher.destination(), result.code());
            } else {
                repository.markFailed(announcement.announcementId(), publisher.destination(), result.code());
            }
        } catch (RuntimeException exception) {
            return AnnouncementPublishResult.failed("DELIVERY_STATE_FAILURE");
        }
        return result;
    }
}
