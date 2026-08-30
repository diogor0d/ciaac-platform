package com.ciaac.minecraft.minigames.statistics;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Map;
import java.util.TreeMap;

/**
 * One participant's terminal standing.
 *
 * <p>Placement {@code 0} means unranked, which is required for cancelled,
 * aborted, and no-contest sessions. Multiple players may be first-place
 * winners when they share a team.</p>
 */
public record PlayerResult(
        int placement,
        boolean winner,
        boolean forfeit,
        Optional<String> teamId,
        Map<String, Long> metrics) {

    public static final int MAX_METRICS = 32;
    public static final long MAX_METRIC_ABS_VALUE = 1_000_000_000L;

    public PlayerResult(int placement, boolean winner, boolean forfeit) {
        this(placement, winner, forfeit, Optional.empty(), Map.of());
    }

    public PlayerResult(int placement, boolean winner, boolean forfeit, Optional<String> teamId) {
        this(placement, winner, forfeit, teamId, Map.of());
    }

    public static PlayerResult forTeam(int placement, boolean winner, boolean forfeit, String teamId) {
        return new PlayerResult(placement, winner, forfeit, Optional.of(teamId), Map.of());
    }

    public static PlayerResult withMetrics(
            int placement,
            boolean winner,
            boolean forfeit,
            String metric,
            long value) {
        return new PlayerResult(
                placement, winner, forfeit, Optional.empty(), java.util.Map.of(metric, value));
    }

    public static PlayerResult forTeamWithMetrics(
            int placement,
            boolean winner,
            boolean forfeit,
            String teamId,
            Map<String, Long> metrics) {
        return new PlayerResult(placement, winner, forfeit, Optional.of(teamId), metrics);
    }

    public PlayerResult {
        if (placement < 0) {
            throw new IllegalArgumentException("placement cannot be negative");
        }
        if (winner && placement != 1) {
            throw new IllegalArgumentException("a winner must be in first place");
        }
        teamId = Objects.requireNonNull(teamId, "teamId");
        teamId.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("teamId cannot be blank");
            }
        });
        Objects.requireNonNull(metrics, "metrics");
        if (metrics.size() > MAX_METRICS) {
            throw new IllegalArgumentException("A player result cannot contain more than " + MAX_METRICS + " metrics");
        }
        var normalizedMetrics = new TreeMap<String, Long>();
        metrics.forEach((key, value) -> {
            String normalizedKey = StatisticsIdentifiers.metricKey(key);
            Objects.requireNonNull(value, "metric value");
            if (value < -MAX_METRIC_ABS_VALUE || value > MAX_METRIC_ABS_VALUE) {
                throw new IllegalArgumentException("metric value exceeds the bounded range");
            }
            if (normalizedMetrics.put(normalizedKey, value) != null) {
                throw new IllegalArgumentException("metric keys must be unique after normalization");
            }
        });
        metrics = java.util.Collections.unmodifiableMap(normalizedMetrics);
    }

    public OptionalLong metric(String key) {
        String normalizedKey = StatisticsIdentifiers.metricKey(key);
        Long value = metrics.get(normalizedKey);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }
}
