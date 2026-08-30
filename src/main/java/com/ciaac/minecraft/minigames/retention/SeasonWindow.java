package com.ciaac.minecraft.minigames.retention;

import java.time.LocalDate;
import java.util.Objects;

/** A named Passport season. End dates are exclusive; grace dates are inclusive. */
public record SeasonWindow(String id, LocalDate startsOn, LocalDate endsOnExclusive, LocalDate graceEndsOnInclusive) {
    public SeasonWindow {
        if (id == null || !id.matches("passport-[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException("season id is invalid");
        }
        Objects.requireNonNull(startsOn, "startsOn");
        Objects.requireNonNull(endsOnExclusive, "endsOnExclusive");
        Objects.requireNonNull(graceEndsOnInclusive, "graceEndsOnInclusive");
        if (!endsOnExclusive.isAfter(startsOn) || graceEndsOnInclusive.isBefore(endsOnExclusive)) {
            throw new IllegalArgumentException("season date range is invalid");
        }
    }

    public SeasonState stateOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (date.isBefore(startsOn)) return SeasonState.FUTURE;
        if (date.isBefore(endsOnExclusive)) return SeasonState.ACTIVE;
        if (!date.isAfter(graceEndsOnInclusive)) return SeasonState.CLAIM_GRACE;
        return SeasonState.ARCHIVED;
    }

    public enum SeasonState { FUTURE, ACTIVE, CLAIM_GRACE, ARCHIVED }
}
