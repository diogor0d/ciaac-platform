package com.ciaac.minecraft.minigames.statistics;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable season and time-window filter for a leaderboard query. */
public record LeaderboardPeriod(
        Optional<String> season,
        Optional<Instant> fromInclusive,
        Optional<Instant> untilExclusive) {

    public LeaderboardPeriod {
        season = Objects.requireNonNull(season, "season");
        fromInclusive = Objects.requireNonNull(fromInclusive, "fromInclusive");
        untilExclusive = Objects.requireNonNull(untilExclusive, "untilExclusive");
        season = season.map(value -> StatisticsIdentifiers.identifier(value, "season"));
        fromInclusive.ifPresent(Objects::requireNonNull);
        untilExclusive.ifPresent(Objects::requireNonNull);
        if (fromInclusive.isPresent() && untilExclusive.isPresent()
                && !fromInclusive.get().isBefore(untilExclusive.get())) {
            throw new IllegalArgumentException("fromInclusive must be before untilExclusive");
        }
    }

    public LeaderboardPeriod(String season) {
        this(Optional.of(season), Optional.empty(), Optional.empty());
    }

    public LeaderboardPeriod(Instant fromInclusive, Instant untilExclusive) {
        this(Optional.empty(), Optional.of(fromInclusive), Optional.of(untilExclusive));
    }

    public static LeaderboardPeriod allTime() {
        return new LeaderboardPeriod(Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static LeaderboardPeriod season(String season) {
        return new LeaderboardPeriod(season);
    }

    public static LeaderboardPeriod between(Instant fromInclusive, Instant untilExclusive) {
        return new LeaderboardPeriod(fromInclusive, untilExclusive);
    }

    public boolean includes(MatchResult result) {
        Objects.requireNonNull(result, "result");
        if (season.isPresent() && !season.get().equals(result.season())) {
            return false;
        }
        if (fromInclusive.isPresent() && result.finishedAt().isBefore(fromInclusive.get())) {
            return false;
        }
        return untilExclusive.isEmpty() || result.finishedAt().isBefore(untilExclusive.get());
    }
}
