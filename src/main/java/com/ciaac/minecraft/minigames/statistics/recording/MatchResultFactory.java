package com.ciaac.minecraft.minigames.statistics.recording;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.statistics.MatchOutcome;
import com.ciaac.minecraft.minigames.statistics.MatchResult;
import com.ciaac.minecraft.minigames.statistics.PlayerResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Small, bounded builders shared by Paper controllers when projecting domain results. */
public final class MatchResultFactory {
    public static final String DEFAULT_SEASON = "unseasoned";

    private MatchResultFactory() {
    }

    public static MatchResult create(
            UUID matchId,
            GameKey game,
            String ruleset,
            String mode,
            Instant startedAt,
            Instant finishedAt,
            MatchOutcome outcome,
            String reasonCode,
            Map<UUID, PlayerResult> players) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Instant boundedStart = startedAt == null || startedAt.isAfter(finishedAt)
                ? finishedAt : startedAt;
        return new MatchResult(
                ResultIds.forMatch(matchId), matchId, game,
                identifier(ruleset, game.id()), identifier(mode, "default"),
                DEFAULT_SEASON, boundedStart, finishedAt, outcome,
                reason(reasonCode, "TERMINAL"), players);
    }

    public static MatchResult noContest(
            UUID matchId,
            GameKey game,
            String ruleset,
            String mode,
            Instant startedAt,
            Instant finishedAt,
            String reasonCode,
            Collection<UUID> players) {
        Objects.requireNonNull(players, "players");
        Map<UUID, PlayerResult> standings = new LinkedHashMap<>();
        for (UUID player : players) {
            standings.put(Objects.requireNonNull(player, "player"),
                    new PlayerResult(0, false, false, Optional.empty(), Map.of()));
        }
        return create(matchId, game, ruleset, mode, startedAt, finishedAt,
                MatchOutcome.NO_CONTEST, reasonCode, standings);
    }

    public static PlayerResult standing(
            int placement,
            boolean winner,
            boolean forfeit,
            String teamId,
            Map<String, Long> metrics) {
        return new PlayerResult(placement, winner, forfeit,
                Optional.ofNullable(teamId), metrics == null ? Map.of() : metrics);
    }

    public static long boundedMillis(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) return 0L;
        try {
            return Math.min(PlayerResult.MAX_METRIC_ABS_VALUE, duration.toMillis());
        } catch (ArithmeticException overflow) {
            return PlayerResult.MAX_METRIC_ABS_VALUE;
        }
    }

    public static long bounded(long value) {
        return Math.max(-PlayerResult.MAX_METRIC_ABS_VALUE,
                Math.min(PlayerResult.MAX_METRIC_ABS_VALUE, value));
    }

    /** Returns a stable fallback rather than allowing a malformed config to break restoration. */
    public static String identifier(String value, String fallback) {
        String candidate = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        String safeFallback = Objects.requireNonNull(fallback, "fallback").trim().toLowerCase(Locale.ROOT);
        return candidate.matches("[a-z0-9][a-z0-9_.-]{0,31}")
                ? candidate
                : safeFallback;
    }

    /** Returns a bounded machine reason while preserving valid domain reasons. */
    public static String reason(String value, String fallback) {
        String candidate = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        String safeFallback = Objects.requireNonNull(fallback, "fallback").trim().toUpperCase(Locale.ROOT);
        return candidate.matches("[A-Z0-9][A-Z0-9_.-]{0,63}")
                ? candidate
                : safeFallback;
    }
}
