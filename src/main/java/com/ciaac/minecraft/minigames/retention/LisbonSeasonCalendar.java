package com.ciaac.minecraft.minigames.retention;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Three-calendar-month Passport seasons anchored at 2026-09-15 in Lisbon. */
public final class LisbonSeasonCalendar {
    public static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");
    public static final LocalDate ANCHOR = LocalDate.of(2026, 9, 15);
    private static final DateTimeFormatter ID_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private final ZoneId zone;
    private final LocalDate anchor;
    private final int graceDays;

    public LisbonSeasonCalendar() { this(LISBON, ANCHOR, 14); }

    public LisbonSeasonCalendar(ZoneId zone, LocalDate anchor, int graceDays) {
        this.zone = Objects.requireNonNull(zone, "zone");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        if (graceDays < 0 || graceDays > 31) throw new IllegalArgumentException("graceDays is invalid");
        this.graceDays = graceDays;
    }

    public ZoneId zone() { return zone; }
    public LocalDate localDate(Instant instant) {
        return Objects.requireNonNull(instant, "instant").atZone(zone).toLocalDate();
    }
    public SeasonWindow seasonAt(Instant instant) { return seasonContaining(localDate(instant)); }
    public SeasonWindow seasonById(String id) {
        if (id == null || !id.matches("passport-[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException("Identificador de época inválido.");
        }
        LocalDate start = LocalDate.parse(id.substring("passport-".length()));
        SeasonWindow season = seasonContaining(start);
        if (!season.startsOn().equals(start)) throw new IllegalArgumentException("A data não é uma fronteira de época.");
        return season;
    }

    public SeasonWindow seasonContaining(LocalDate date) {
        Objects.requireNonNull(date, "date");
        long months = (long) (date.getYear() - anchor.getYear()) * 12 + date.getMonthValue() - anchor.getMonthValue();
        long periods = Math.floorDiv(months, 3);
        LocalDate start = anchor.plusMonths(periods * 3);
        while (date.isBefore(start)) start = start.minusMonths(3);
        while (!date.isBefore(start.plusMonths(3))) start = start.plusMonths(3);
        LocalDate end = start.plusMonths(3);
        return new SeasonWindow("passport-" + ID_DATE.format(start), start, end, end.plusDays(graceDays));
    }

    public ZonedDateTime startsAt(SeasonWindow season) {
        return Objects.requireNonNull(season, "season").startsOn().atStartOfDay(zone);
    }
}
