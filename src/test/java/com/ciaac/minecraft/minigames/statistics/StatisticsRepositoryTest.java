package com.ciaac.minecraft.minigames.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class StatisticsRepositoryTest {
    private static final Instant START = Instant.parse("2026-08-22T12:00:00Z");
    private static final Instant FIRST = Instant.parse("2026-08-22T12:10:00Z");
    private static final Instant SECOND = Instant.parse("2026-08-22T12:20:00Z");
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PLAYER_C = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void compatibilityConstructorUsesExplicitSafeDefaults() {
        MatchResult result = new MatchResult(
                UUID.randomUUID(), UUID.randomUUID(), GameKey.ARENA, FIRST,
                MatchOutcome.VICTORY, "completed",
                Map.of(PLAYER_A, PlayerResult.withMetrics(1, true, false, "Score", 4)));

        assertEquals("default", result.ruleset());
        assertEquals("default", result.mode());
        assertEquals("unseasoned", result.season());
        assertEquals(FIRST, result.startedAt());
        assertEquals("COMPLETED", result.reasonCode());
    }

    @Test
    void envelopeNormalizesRulesetModeSeasonAndFreezesMetrics() {
        var source = new java.util.HashMap<String, Long>();
        source.put("Score", 7L);
        PlayerResult player = new PlayerResult(1, true, false, java.util.Optional.empty(), source);
        source.put("score", 99L);

        MatchResult result = new MatchResult(
                UUID.randomUUID(), UUID.randomUUID(), GameKey.ARENA,
                " Duel.V1 ", " 1V1 ", " Season-1 ", START, FIRST,
                MatchOutcome.VICTORY, "completed", Map.of(PLAYER_A, player));

        assertEquals("duel.v1", result.ruleset());
        assertEquals("1v1", result.mode());
        assertEquals("season-1", result.season());
        assertEquals(7L, player.metrics().get("score"));
        assertThrows(UnsupportedOperationException.class, () -> player.metrics().put("other", 1L));
    }

    @Test
    void metricValuesAndKeysAreBounded() {
        assertThrows(IllegalArgumentException.class,
                () -> PlayerResult.withMetrics(1, true, false, "score", 1_000_000_001L));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerResult.withMetrics(1, true, false, "not a metric", 1L));
        assertThrows(IllegalArgumentException.class, () -> new PlayerResult(
                1, true, false, java.util.Optional.empty(),
                Map.of("score", 1L, "SCORE", 2L)));
    }

    @Test
    void repositoryAppliesIdenticalReplaysAndRejectsConflicts() {
        var repository = new InMemoryStatisticsRepository();
        UUID resultId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        MatchResult first = result(resultId, matchId, FIRST, MatchOutcome.VICTORY,
                Map.of(PLAYER_A, score(1, true, 10L)));

        assertTrue(repository.record(first));
        assertFalse(repository.record(first));
        assertEquals(first, repository.findByResultId(resultId).orElseThrow());
        assertEquals(first, repository.findByMatchId(matchId).orElseThrow());

        MatchResult sameResultDifferentPayload = result(resultId, matchId, FIRST,
                MatchOutcome.VICTORY, Map.of(PLAYER_A, score(1, true, 11L)));
        assertThrows(IllegalStateException.class, () -> repository.record(sameResultDifferentPayload));

        MatchResult sameMatchDifferentResult = result(UUID.randomUUID(), matchId, SECOND,
                MatchOutcome.VICTORY, Map.of(PLAYER_A, score(1, true, 12L)));
        assertThrows(IllegalStateException.class, () -> repository.record(sameMatchDifferentResult));
    }

    @Test
    void leaderboardFiltersByRulesetModeSeasonAndMinimumSamplesWithStableTies() {
        var repository = new InMemoryStatisticsRepository();
        repository.record(result(UUID.randomUUID(), UUID.randomUUID(), FIRST, MatchOutcome.VICTORY,
                Map.of(
                        PLAYER_A, score(1, true, 10L),
                        PLAYER_B, score(2, false, 10L))));
        repository.record(result(UUID.randomUUID(), UUID.randomUUID(), SECOND, MatchOutcome.VICTORY,
                Map.of(
                        PLAYER_A, score(1, true, 15L),
                        PLAYER_B, score(2, false, 15L))));
        repository.record(resultWithEnvelope(UUID.randomUUID(), UUID.randomUUID(), SECOND,
                "other-rules", "1v1", "season-2", MatchOutcome.VICTORY,
                Map.of(PLAYER_C, score(1, true, 100L))));

        LeaderboardQuery query = new LeaderboardQuery(
                GameKey.ARENA, "duel-v1", "1v1", "score", MetricAggregation.SUM,
                LeaderboardDirection.HIGHER_IS_BETTER, LeaderboardPeriod.season("season-1"), 2, 1);
        var top = repository.leaderboard(query);

        assertEquals(1, top.size());
        assertEquals(PLAYER_A, top.getFirst().playerId());
        assertEquals(25L, top.getFirst().value().longValueExact());
        assertEquals(2L, top.getFirst().sampleCount());
        assertEquals(1, repository.position(query, PLAYER_B).orElseThrow().rank());
        assertTrue(repository.position(query, PLAYER_C).isEmpty());
    }

    @Test
    void leaderboardSupportsAverageLowerIsBetterAndTimeWindows() {
        var repository = new InMemoryStatisticsRepository();
        repository.record(resultWithEnvelope(UUID.randomUUID(), UUID.randomUUID(), FIRST,
                "duel-v1", "1v1", "season-1", MatchOutcome.VICTORY,
                Map.of(PLAYER_A, score(1, true, 40L), PLAYER_B, score(2, false, 50L))));
        repository.record(resultWithEnvelope(UUID.randomUUID(), UUID.randomUUID(), SECOND,
                "duel-v1", "1v1", "season-1", MatchOutcome.DRAW,
                Map.of(PLAYER_A, score(1, false, 20L), PLAYER_B, score(1, false, 10L))));

        LeaderboardQuery query = new LeaderboardQuery(
                GameKey.ARENA, "duel-v1", "1v1", "score", MetricAggregation.AVERAGE,
                LeaderboardDirection.LOWER_IS_BETTER,
                LeaderboardPeriod.between(Instant.parse("2026-08-22T12:15:00Z"),
                        Instant.parse("2026-08-22T12:30:00Z")),
                1, 10);
        var rows = repository.leaderboard(query);

        assertEquals(2, rows.size());
        assertEquals(10L, rows.getFirst().value().longValueExact());
        assertEquals(PLAYER_B, rows.getFirst().playerId());
        assertEquals(1, repository.rank(query, PLAYER_B).orElseThrow());
    }

    @Test
    void unrankedTerminalResultsRemainEvidenceButDoNotCreateSamples() {
        var repository = new InMemoryStatisticsRepository();
        repository.record(result(UUID.randomUUID(), UUID.randomUUID(), FIRST, MatchOutcome.NO_CONTEST,
                Map.of(PLAYER_A, new PlayerResult(0, false, false, java.util.Optional.empty(),
                        Map.of("score", 99L)))));

        assertTrue(repository.leaderboard(new LeaderboardQuery(GameKey.ARENA, "score")).isEmpty());
        assertEquals(1, repository.resultCount());
    }

    @Test
    void discoversOneExactScopeAndPresetRejectsDifferentProjection() {
        var repository = new InMemoryStatisticsRepository();
        repository.record(resultWithEnvelope(UUID.randomUUID(), UUID.randomUUID(), FIRST,
                "duel-v1", "1v1", "season-1", MatchOutcome.VICTORY,
                Map.of(PLAYER_A, score(1, true, 10L))));

        LeaderboardPreset preset = new LeaderboardPreset(
                GameKey.ARENA, "score", "pontuação", MetricAggregation.SUM,
                LeaderboardDirection.HIGHER_IS_BETTER);
        LeaderboardScope scope = new LeaderboardScope(GameKey.ARENA, "duel-v1", "1v1", "score");

        assertEquals(List.of(scope), repository.discoverScopes(GameKey.ARENA, "SCORE"));
        assertEquals(1, repository.leaderboard(preset.query(scope, 10)).size());
        assertThrows(IllegalArgumentException.class,
                () -> preset.query(new LeaderboardScope(GameKey.ARENA, "duel-v1", "1v1", "wins"), 10));
    }

    @Test
    void discoversMultipleScopesDeterministicallyAndScopedQueriesDoNotMixThem() {
        var repository = new InMemoryStatisticsRepository();
        repository.record(resultWithEnvelope(UUID.randomUUID(), UUID.randomUUID(), FIRST,
                "z-rules", "2v2", "season-1", MatchOutcome.VICTORY,
                Map.of(PLAYER_A, score(1, true, 100L))));
        repository.record(resultWithEnvelope(UUID.randomUUID(), UUID.randomUUID(), SECOND,
                "a-rules", "1v1", "season-1", MatchOutcome.VICTORY,
                Map.of(PLAYER_A, score(1, true, 5L))));
        repository.record(resultWithEnvelope(UUID.randomUUID(), UUID.randomUUID(), SECOND,
                "a-rules", "3v3", "season-1", MatchOutcome.VICTORY,
                Map.of(PLAYER_A, score(1, true, 7L))));

        List<LeaderboardScope> scopes = repository.discoverScopes(GameKey.ARENA, "score");
        assertEquals(List.of(
                new LeaderboardScope(GameKey.ARENA, "a-rules", "1v1", "score"),
                new LeaderboardScope(GameKey.ARENA, "a-rules", "3v3", "score"),
                new LeaderboardScope(GameKey.ARENA, "z-rules", "2v2", "score")), scopes);

        LeaderboardPreset preset = new LeaderboardPreset(
                GameKey.ARENA, "score", "pontuação", MetricAggregation.SUM,
                LeaderboardDirection.HIGHER_IS_BETTER);
        assertEquals(5L, repository.leaderboard(preset.query(scopes.getFirst(), 10))
                .getFirst().value().longValueExact());
    }

    private static PlayerResult score(int placement, boolean winner, long value) {
        return PlayerResult.withMetrics(placement, winner, false, "score", value);
    }

    private static MatchResult result(
            UUID resultId,
            UUID matchId,
            Instant finishedAt,
            MatchOutcome outcome,
            Map<UUID, PlayerResult> players) {
        return resultWithEnvelope(resultId, matchId, finishedAt,
                "duel-v1", "1v1", "season-1", outcome, players);
    }

    private static MatchResult resultWithEnvelope(
            UUID resultId,
            UUID matchId,
            Instant finishedAt,
            String ruleset,
            String mode,
            String season,
            MatchOutcome outcome,
            Map<UUID, PlayerResult> players) {
        return new MatchResult(
                resultId, matchId, GameKey.ARENA, ruleset, mode, season,
                START, finishedAt, outcome,
                outcome == MatchOutcome.NO_CONTEST ? "SERVER_INTERRUPTION" : "COMPLETED",
                players);
    }
}
