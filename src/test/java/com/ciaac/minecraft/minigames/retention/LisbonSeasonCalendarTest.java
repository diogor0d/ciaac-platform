package com.ciaac.minecraft.minigames.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LisbonSeasonCalendarTest {
    private final LisbonSeasonCalendar calendar = new LisbonSeasonCalendar();

    @Test void anchorAndThreeMonthBoundariesAreStable() {
        SeasonWindow first = calendar.seasonContaining(LocalDate.of(2026, 9, 15));
        assertEquals("passport-2026-09-15", first.id());
        assertEquals(LocalDate.of(2026, 12, 15), first.endsOnExclusive());
        assertEquals(LocalDate.of(2026, 12, 29), first.graceEndsOnInclusive());
        assertEquals("passport-2026-12-15", calendar.seasonContaining(LocalDate.of(2026, 12, 15)).id());
    }

    @Test void LisbonDstDateControlsSeasonRatherThanUtcDate() {
        Instant beforeLisbonMidnight = Instant.parse("2027-03-28T22:30:00Z");
        Instant afterLisbonMidnight = Instant.parse("2027-03-29T00:30:00Z");
        assertEquals(LocalDate.of(2027, 3, 28), calendar.localDate(beforeLisbonMidnight));
        assertEquals(LocalDate.of(2027, 3, 29), calendar.localDate(afterLisbonMidnight));
        assertTrue(calendar.seasonAt(afterLisbonMidnight).startsOn().isBefore(calendar.seasonAt(afterLisbonMidnight).endsOnExclusive()));
    }

    @Test void claimGraceLastsFourteenLocalDatesAfterSeasonClose() {
        SeasonWindow first = calendar.seasonContaining(LocalDate.of(2026, 9, 15));
        assertEquals(SeasonWindow.SeasonState.CLAIM_GRACE, first.stateOn(LocalDate.of(2026, 12, 29)));
        assertEquals(SeasonWindow.SeasonState.ARCHIVED, first.stateOn(LocalDate.of(2026, 12, 30)));
    }
}
