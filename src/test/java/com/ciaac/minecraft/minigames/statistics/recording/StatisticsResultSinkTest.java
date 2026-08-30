package com.ciaac.minecraft.minigames.statistics.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.statistics.InMemoryStatisticsRepository;
import com.ciaac.minecraft.minigames.statistics.MatchOutcome;
import com.ciaac.minecraft.minigames.statistics.MatchResult;
import com.ciaac.minecraft.minigames.statistics.PlayerResult;
import com.ciaac.minecraft.minigames.statistics.StatisticsRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class StatisticsResultSinkTest {
    private static final Instant FINISHED = Instant.parse("2026-08-22T12:00:00Z");
    private static final UUID MATCH = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void derivesStableIdentityAndAppliesAResultOnlyOnce() {
        var repository = new InMemoryStatisticsRepository();
        var sink = new StatisticsResultSink(repository);
        MatchResult result = victory();

        assertEquals(ResultIds.forMatch(MATCH), result.resultId());
        assertEquals(ResultRecording.Status.RECORDED, sink.record(result).status());
        assertEquals(ResultRecording.Status.IDEMPOTENT_REPLAY, sink.record(result).status());
        assertEquals(1, repository.resultCount());
    }

    @Test
    void repositoryReplayDoesNotReemitCommittedSideEffects() {
        var repository = new InMemoryStatisticsRepository();
        MatchResult result = victory();
        assertTrue(repository.record(result));

        var callbacks = new AtomicInteger();
        var sink = new StatisticsResultSink(repository, ignored -> callbacks.incrementAndGet());

        assertEquals(ResultRecording.Status.IDEMPOTENT_REPLAY, sink.record(result).status());
        assertEquals(0, callbacks.get());
    }

    @Test
    void rejectsAResultWithARandomIdentityBeforePersistence() {
        var sink = new StatisticsResultSink(new InMemoryStatisticsRepository());
        MatchResult randomIdentity = new MatchResult(
                UUID.randomUUID(), MATCH, GameKey.KNOCKBACK_SUMO,
                "sumo-v1", "1v1", MatchResultFactory.DEFAULT_SEASON,
                FINISHED, FINISHED, MatchOutcome.VICTORY, "COMPLETED",
                Map.of(PLAYER_A, new PlayerResult(1, true, false),
                        PLAYER_B, new PlayerResult(2, false, false)));

        assertThrows(IllegalArgumentException.class, () -> sink.record(randomIdentity));
    }

    @Test
    void unavailableSinkNeverClaimsADurableCommit() {
        ResultRecording recording = StatisticsResultSink.unavailable().record(victory());

        assertEquals(ResultRecording.Status.UNAVAILABLE, recording.status());
        assertFalse(recording.committed());
    }

    @Test
    void reportsRepositoryFailuresWithoutChangingTheGameplayCallPath() {
        var failures = new java.util.ArrayList<ResultRecording>();
        StatisticsRepository repository = new FailingStatisticsRepository();
        var sink = new StatisticsResultSink(repository, ignored -> { }, failures::add);

        ResultRecording recording = sink.record(victory());

        assertEquals(ResultRecording.Status.FAILED, recording.status());
        assertEquals("STATISTICS_FAILURE", recording.code());
        assertEquals(java.util.List.of(recording), failures);
    }

    @Test
    void noContestEvidenceDoesNotProjectToTheLeaderboard() {
        var repository = new InMemoryStatisticsRepository();
        var sink = new StatisticsResultSink(repository);
        MatchResult result = MatchResultFactory.noContest(
                MATCH, GameKey.CHECKPOINT_PARKOUR, "parkour-v1", "solo",
                FINISHED, FINISHED, "PLAYER_LEFT", Set.of(PLAYER_A));

        assertTrue(sink.record(result).committed());
        assertTrue(repository.leaderboard(
                new com.ciaac.minecraft.minigames.statistics.LeaderboardQuery(
                        GameKey.CHECKPOINT_PARKOUR, "time_ms")).isEmpty());
        assertEquals(1, repository.resultCount());
    }

    private static MatchResult victory() {
        return MatchResultFactory.create(
                MATCH, GameKey.KNOCKBACK_SUMO, "sumo-v1", "1v1",
                FINISHED, FINISHED, MatchOutcome.VICTORY, "COMPLETED",
                Map.of(
                        PLAYER_A, MatchResultFactory.standing(1, true, false, null,
                                Map.of("wins", 1L, "rounds", 3L)),
                        PLAYER_B, MatchResultFactory.standing(2, false, false, null,
                                Map.of("wins", 0L, "rounds", 3L))));
    }

    private static final class FailingStatisticsRepository implements StatisticsRepository {
        @Override public boolean record(MatchResult result) { throw new RuntimeException("synthetic failure"); }
        @Override public java.util.Optional<MatchResult> findByResultId(UUID resultId) { return java.util.Optional.empty(); }
        @Override public java.util.Optional<MatchResult> findByMatchId(UUID matchId) { return java.util.Optional.empty(); }
        @Override public java.util.List<com.ciaac.minecraft.minigames.statistics.LeaderboardScope> discoverScopes(
                com.ciaac.minecraft.minigames.core.GameKey game, String metric) { return java.util.List.of(); }
        @Override public java.util.List<com.ciaac.minecraft.minigames.statistics.LeaderboardEntry> leaderboard(
                com.ciaac.minecraft.minigames.statistics.LeaderboardQuery query) { return java.util.List.of(); }
        @Override public java.util.Optional<com.ciaac.minecraft.minigames.statistics.LeaderboardEntry> position(
                com.ciaac.minecraft.minigames.statistics.LeaderboardQuery query, UUID playerId) {
            return java.util.Optional.empty();
        }
    }
}
