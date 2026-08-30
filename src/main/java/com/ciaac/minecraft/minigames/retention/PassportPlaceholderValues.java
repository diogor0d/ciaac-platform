package com.ciaac.minecraft.minigames.retention;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only PlaceholderAPI-facing values. The Paper expansion registration is intentionally outside this package. */
public final class PassportPlaceholderValues {
    private final PassportService service;
    public PassportPlaceholderValues(PassportService service) { this.service = Objects.requireNonNull(service, "service"); }

    public Optional<String> value(UUID playerId, String parameter, Instant now) {
        if (playerId == null || parameter == null) return Optional.empty();
        PassportService.PassportSnapshot snapshot = service.passport(playerId, now);
        return switch (parameter.toLowerCase(Locale.ROOT)) {
            case "season" -> Optional.of(snapshot.season().id());
            case "join_streak" -> Optional.of(Integer.toString(snapshot.currentJoinStreak()));
            case "longest_join_streak" -> Optional.of(Integer.toString(snapshot.longestJoinStreak()));
            case "active_days" -> Optional.of(Integer.toString(snapshot.activeDays()));
            case "weekly_objectives" -> Optional.of(Integer.toString(snapshot.weeklyObjectives()));
            case "points" -> Optional.of(Integer.toString(snapshot.points()));
            case "join_freeze" -> Optional.of(snapshot.joinFreezeAvailable() ? "disponível" : "indisponível");
            case "title" -> Optional.of(snapshot.selections().getOrDefault("titulo", ""));
            case "badge" -> Optional.of(snapshot.selections().getOrDefault("distintivo", ""));
            case "chat_badge" -> Optional.of(snapshot.selections().containsKey("distintivo")
                    ? "[" + snapshot.selections().get("distintivo") + "]" : "");
            case "rank_join_streak" -> Optional.of(Integer.toString(service.rank(playerId,
                    PassportService.LeaderboardMetric.CURRENT_JOIN_STREAK, now)));
            case "rank_active_days" -> Optional.of(Integer.toString(service.rank(playerId,
                    PassportService.LeaderboardMetric.ACTIVE_DAYS, now)));
            case "rank_points" -> Optional.of(Integer.toString(service.rank(playerId,
                    PassportService.LeaderboardMetric.PASSPORT_POINTS, now)));
            default -> Optional.empty();
        };
    }
}
