package com.ciaac.minecraft.minigames.retention;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Append-only, pseudonymous event; `sourceId` is the idempotency key. */
public record RetentionEvent(UUID eventId, UUID sourceId, UUID playerId, Instant occurredAt,
                             LocalDate localDate, Kind kind, String seasonId, int points) {
    public RetentionEvent {
        eventId = Objects.requireNonNull(eventId, "eventId"); sourceId = Objects.requireNonNull(sourceId, "sourceId");
        playerId = Objects.requireNonNull(playerId, "playerId"); occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        localDate = Objects.requireNonNull(localDate, "localDate"); kind = Objects.requireNonNull(kind, "kind");
        if (seasonId == null || seasonId.isBlank() || points < 0 || points > 25) throw new IllegalArgumentException("retention event is invalid");
    }
    public enum Kind { JOIN_QUALIFIED, ACTIVE_MINUTE, ACTIVE_DAY, WEEKLY_OBJECTIVE, JOIN_FREEZE_USED, SEASON_MILESTONE }
}
