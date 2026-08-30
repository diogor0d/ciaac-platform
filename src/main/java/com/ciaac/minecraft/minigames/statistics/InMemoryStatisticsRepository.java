package com.ciaac.minecraft.minigames.statistics;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * In-memory reference implementation of the statistics repository contract.
 *
 * <p>This implementation is useful for pure-domain tests and previews. It is
 * intentionally not a restart-safe production store.</p>
 */
public final class InMemoryStatisticsRepository implements StatisticsRepository {
    private final Map<UUID, MatchResult> resultsById = new HashMap<>();
    private final Map<UUID, UUID> resultIdByMatchId = new HashMap<>();

    @Override
    public synchronized boolean record(MatchResult result) {
        Objects.requireNonNull(result, "result");
        MatchResult previous = resultsById.get(result.resultId());
        if (previous != null) {
            if (!previous.equals(result)) {
                throw new IllegalStateException("Conflicting replay for result " + result.resultId());
            }
            return false;
        }
        UUID previousResultId = resultIdByMatchId.get(result.matchId());
        if (previousResultId != null) {
            throw new IllegalStateException(
                    "Match " + result.matchId() + " already has result " + previousResultId);
        }
        resultsById.put(result.resultId(), result);
        resultIdByMatchId.put(result.matchId(), result.resultId());
        return true;
    }

    @Override
    public synchronized Optional<MatchResult> findByResultId(UUID resultId) {
        return Optional.ofNullable(resultsById.get(Objects.requireNonNull(resultId, "resultId")));
    }

    @Override
    public synchronized Optional<MatchResult> findByMatchId(UUID matchId) {
        UUID resultId = resultIdByMatchId.get(Objects.requireNonNull(matchId, "matchId"));
        return resultId == null ? Optional.empty() : Optional.of(resultsById.get(resultId));
    }

    @Override
    public synchronized List<LeaderboardScope> discoverScopes(GameKey game, String metric) {
        Objects.requireNonNull(game, "game");
        String normalizedMetric = StatisticsIdentifiers.metricKey(metric);
        Set<ScopeKey> scopes = new TreeSet<>();
        for (MatchResult result : resultsById.values()) {
            if (!result.outcome().ranked() || result.game() != game) continue;
            if (result.players().values().stream()
                    .noneMatch(player -> player.metrics().containsKey(normalizedMetric))) continue;
            scopes.add(new ScopeKey(result.ruleset(), result.mode()));
        }
        return scopes.stream()
                .map(scope -> new LeaderboardScope(game, scope.ruleset(), scope.mode(), normalizedMetric))
                .toList();
    }

    @Override
    public synchronized List<LeaderboardEntry> leaderboard(LeaderboardQuery query) {
        Objects.requireNonNull(query, "query");
        List<LeaderboardEntry> projected = project(query);
        return List.copyOf(projected.subList(0, Math.min(query.limit(), projected.size())));
    }

    @Override
    public synchronized Optional<LeaderboardEntry> position(LeaderboardQuery query, UUID playerId) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(playerId, "playerId");
        return project(query).stream()
                .filter(entry -> entry.playerId().equals(playerId))
                .findFirst();
    }

    /** Number of immutable result envelopes held by this reference store. */
    public synchronized int resultCount() {
        return resultsById.size();
    }

    private List<LeaderboardEntry> project(LeaderboardQuery query) {
        Map<UUID, Aggregate> aggregates = new HashMap<>();
        for (MatchResult result : resultsById.values()) {
            if (!result.outcome().ranked() || !query.matches(result)) {
                continue;
            }
            result.players().forEach((playerId, playerResult) -> {
                Long metricValue = playerResult.metrics().get(query.metric());
                if (metricValue != null) {
                    aggregates.computeIfAbsent(playerId, ignored -> new Aggregate()).add(metricValue);
                }
            });
        }

        var ranked = new ArrayList<RankedAggregate>();
        aggregates.forEach((playerId, aggregate) -> {
            if (aggregate.samples >= query.minimumSamples()) {
                ranked.add(new RankedAggregate(playerId, aggregate));
            }
        });
        ranked.sort((left, right) -> {
            int valueComparison = left.aggregate.compareTo(right.aggregate, query.aggregation());
            if (query.direction() == LeaderboardDirection.HIGHER_IS_BETTER) {
                valueComparison = -valueComparison;
            }
            return valueComparison != 0
                    ? valueComparison
                    : left.playerId.compareTo(right.playerId);
        });

        var entries = new ArrayList<LeaderboardEntry>(ranked.size());
        Aggregate previous = null;
        int rank = 0;
        for (int index = 0; index < ranked.size(); index++) {
            RankedAggregate current = ranked.get(index);
            if (previous == null
                    || previous.compareTo(current.aggregate, query.aggregation()) != 0) {
                rank = index + 1;
                previous = current.aggregate;
            }
            entries.add(new LeaderboardEntry(
                    rank,
                    current.playerId,
                    current.aggregate.value(query.aggregation()),
                    current.aggregate.samples));
        }
        return entries;
    }

    private static final class Aggregate {
        private BigDecimal total = BigDecimal.ZERO;
        private BigDecimal maximum;
        private BigDecimal minimum;
        private long samples;

        private void add(long value) {
            BigDecimal decimal = BigDecimal.valueOf(value);
            total = total.add(decimal);
            maximum = maximum == null || decimal.compareTo(maximum) > 0 ? decimal : maximum;
            minimum = minimum == null || decimal.compareTo(minimum) < 0 ? decimal : minimum;
            samples++;
        }

        private int compareTo(Aggregate other, MetricAggregation aggregation) {
            return switch (aggregation) {
                case SUM -> total.compareTo(other.total);
                case AVERAGE -> total.multiply(BigDecimal.valueOf(other.samples))
                        .compareTo(other.total.multiply(BigDecimal.valueOf(samples)));
                case MAX -> maximum.compareTo(other.maximum);
                case MIN -> minimum.compareTo(other.minimum);
            };
        }

        private BigDecimal value(MetricAggregation aggregation) {
            return switch (aggregation) {
                case SUM -> total;
                case AVERAGE -> total.divide(BigDecimal.valueOf(samples), 12, RoundingMode.HALF_UP)
                        .stripTrailingZeros();
                case MAX -> maximum;
                case MIN -> minimum;
            };
        }
    }

    private record RankedAggregate(UUID playerId, Aggregate aggregate) {
    }

    private record ScopeKey(String ruleset, String mode) implements Comparable<ScopeKey> {
        @Override
        public int compareTo(ScopeKey other) {
            int rulesetOrder = ruleset.compareTo(other.ruleset);
            return rulesetOrder != 0 ? rulesetOrder : mode.compareTo(other.mode);
        }
    }
}
