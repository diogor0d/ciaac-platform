package com.ciaac.minecraft.minigames.statistics;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record MatchResult(
        UUID resultId,
        UUID matchId,
        GameKey game,
        String ruleset,
        String mode,
        String season,
        Instant startedAt,
        Instant finishedAt,
        MatchOutcome outcome,
        String reasonCode,
        Map<UUID, PlayerResult> players) {

    /** Compatibility constructor retained for existing game-module callers. */
    public MatchResult(
            UUID resultId,
            UUID matchId,
            GameKey game,
            Instant finishedAt,
            MatchOutcome outcome,
            String reasonCode,
            Map<UUID, PlayerResult> players) {
        this(resultId, matchId, game, "default", "default", "unseasoned", finishedAt,
                finishedAt, outcome, reasonCode, players);
    }

    public MatchResult {
        Objects.requireNonNull(resultId, "resultId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(game, "game");
        ruleset = StatisticsIdentifiers.identifier(ruleset, "ruleset");
        mode = StatisticsIdentifiers.identifier(mode, "mode");
        season = StatisticsIdentifiers.identifier(season, "season");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        if (startedAt.isAfter(finishedAt)) {
            throw new IllegalArgumentException("startedAt cannot be after finishedAt");
        }
        Objects.requireNonNull(outcome, "outcome");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode").trim().toUpperCase(Locale.ROOT);
        if (!reasonCode.matches("[A-Z0-9][A-Z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("reasonCode must be a bounded machine-readable value");
        }
        Objects.requireNonNull(players, "players");
        players = Map.copyOf(players);
        if (players.isEmpty()) {
            throw new IllegalArgumentException("A match result must contain players");
        }
        var winners = players.values().stream().filter(PlayerResult::winner).toList();
        if (outcome == MatchOutcome.VICTORY) {
            if (winners.isEmpty()) {
                throw new IllegalArgumentException("A victory result must have at least one winner");
            }
            if (winners.size() > 1) {
                Set<String> winningTeams = winners.stream()
                        .map(PlayerResult::teamId)
                        .flatMap(java.util.Optional::stream)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                if (winningTeams.size() != 1 || winners.stream().anyMatch(result -> result.teamId().isEmpty())) {
                    throw new IllegalArgumentException(
                            "Multiple winners must identify the same immutable team");
                }
            }
        } else if (!winners.isEmpty()) {
            throw new IllegalArgumentException("Only a victory result may contain winners");
        }

        if (outcome == MatchOutcome.DRAW) {
            long firstPlaces = players.values().stream().filter(result -> result.placement() == 1).count();
            if (firstPlaces < 2) {
                throw new IllegalArgumentException("A draw needs at least two first-place participants");
            }
        }

        if (outcome == MatchOutcome.NO_CONTEST
                || outcome == MatchOutcome.CANCELLED
                || outcome == MatchOutcome.ABORTED) {
            boolean hasRankedPlayer = players.values().stream().anyMatch(result -> result.placement() != 0);
            if (hasRankedPlayer) {
                throw new IllegalArgumentException("Non-competitive terminal results must be unranked");
            }
        }
    }
}
