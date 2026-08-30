package com.ciaac.minecraft.minigames.hotpotato;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable match totals plus player-specific evidence for fair statistics. */
public record HotPotatoMetrics(
        int passes,
        int eliminations,
        int forfeits,
        Duration longestCarrierTime,
        String rulesetRevision,
        Map<UUID, Integer> passesByPlayer,
        Map<UUID, Integer> eliminationsByPlayer,
        Map<UUID, Integer> forfeitsByPlayer,
        Map<UUID, Duration> carrierTimeByPlayer,
        Map<UUID, Duration> survivalTimeByPlayer) {

    public HotPotatoMetrics {
        if (passes < 0 || eliminations < 0 || forfeits < 0
                || longestCarrierTime == null || longestCarrierTime.isNegative()) {
            throw new IllegalArgumentException("invalid metrics");
        }
        rulesetRevision = Objects.requireNonNull(rulesetRevision, "rulesetRevision");
        passesByPlayer = nonNegativeCounts(passesByPlayer, "passesByPlayer");
        eliminationsByPlayer = nonNegativeCounts(eliminationsByPlayer, "eliminationsByPlayer");
        forfeitsByPlayer = nonNegativeCounts(forfeitsByPlayer, "forfeitsByPlayer");
        carrierTimeByPlayer = nonNegativeDurations(carrierTimeByPlayer, "carrierTimeByPlayer");
        survivalTimeByPlayer = nonNegativeDurations(survivalTimeByPlayer, "survivalTimeByPlayer");
    }

    public HotPotatoMetrics(
            int passes, int eliminations, int forfeits,
            Duration longestCarrierTime, String rulesetRevision) {
        this(passes, eliminations, forfeits, longestCarrierTime, rulesetRevision,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static Map<UUID, Integer> nonNegativeCounts(Map<UUID, Integer> source, String name) {
        Objects.requireNonNull(source, name);
        source.forEach((player, count) -> {
            Objects.requireNonNull(player, name + " player");
            if (count == null || count < 0) throw new IllegalArgumentException(name + " contains an invalid count");
        });
        return Map.copyOf(source);
    }

    private static Map<UUID, Duration> nonNegativeDurations(Map<UUID, Duration> source, String name) {
        Objects.requireNonNull(source, name);
        source.forEach((player, duration) -> {
            Objects.requireNonNull(player, name + " player");
            if (duration == null || duration.isNegative()) {
                throw new IllegalArgumentException(name + " contains an invalid duration");
            }
        });
        return Map.copyOf(source);
    }
}
