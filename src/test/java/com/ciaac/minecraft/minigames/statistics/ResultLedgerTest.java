package com.ciaac.minecraft.minigames.statistics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ResultLedgerTest {
    @Test
    void acceptsOneResultAndTreatsAnIdenticalReplayAsIdempotent() {
        ResultLedger ledger = new ResultLedger();
        MatchResult result = result(UUID.randomUUID(), GameKey.HOT_POTATO);

        assertTrue(ledger.record(result));
        assertFalse(ledger.record(result));
    }

    @Test
    void rejectsAConflictingReplayForTheSameMatch() {
        ResultLedger ledger = new ResultLedger();
        UUID matchId = UUID.randomUUID();
        ledger.record(result(matchId, GameKey.ARENA));

        assertThrows(IllegalStateException.class, () -> ledger.record(result(matchId, GameKey.BUILD_BATTLE)));
    }

    @Test
    void rejectsAConflictingPayloadForTheSameResultIdentity() {
        ResultLedger ledger = new ResultLedger();
        UUID resultId = UUID.randomUUID();
        MatchResult first = result(resultId, UUID.randomUUID(), GameKey.ARENA);
        ledger.record(first);

        assertThrows(IllegalStateException.class, () -> ledger.record(
                result(resultId, UUID.randomUUID(), GameKey.HOT_POTATO)));
    }

    @Test
    void indexesTheImmutableResultByBothResultAndMatchIdentity() {
        ResultLedger ledger = new ResultLedger();
        MatchResult result = result(UUID.randomUUID(), GameKey.ARENA);
        ledger.record(result);

        assertEquals(result, ledger.findByResultId(result.resultId()).orElseThrow());
        assertEquals(result, ledger.findByMatchId(result.matchId()).orElseThrow());
    }

    @Test
    void representsTeamVictoriesDrawsAndNoContestsWithoutInventingAWinner() {
        MatchResult teamVictory = new MatchResult(
                UUID.randomUUID(), UUID.randomUUID(), GameKey.ARENA,
                Instant.parse("2026-08-22T12:00:00Z"),
                MatchOutcome.VICTORY, "ELIMINATION",
                Map.of(
                        UUID.randomUUID(), PlayerResult.forTeam(1, true, false, "A"),
                        UUID.randomUUID(), PlayerResult.forTeam(1, true, false, "A"),
                        UUID.randomUUID(), PlayerResult.forTeam(2, false, false, "B")));
        assertEquals(2, teamVictory.players().values().stream().filter(PlayerResult::winner).count());

        MatchResult draw = new MatchResult(
                UUID.randomUUID(), UUID.randomUUID(), GameKey.ARENA,
                Instant.parse("2026-08-22T12:00:00Z"),
                MatchOutcome.DRAW, "TIME_LIMIT",
                Map.of(
                        UUID.randomUUID(), new PlayerResult(1, false, false),
                        UUID.randomUUID(), new PlayerResult(1, false, false)));
        assertTrue(draw.players().values().stream().noneMatch(PlayerResult::winner));

        MatchResult noContest = new MatchResult(
                UUID.randomUUID(), UUID.randomUUID(), GameKey.ARENA,
                Instant.parse("2026-08-22T12:00:00Z"),
                MatchOutcome.NO_CONTEST, "SERVER_INTERRUPTION",
                Map.of(
                        UUID.randomUUID(), new PlayerResult(0, false, false),
                        UUID.randomUUID(), new PlayerResult(0, false, false)));
        assertTrue(noContest.players().values().stream().noneMatch(PlayerResult::winner));
    }

    @Test
    void rejectsAmbiguousMultipleWinnersAndRankedAbort() {
        assertThrows(IllegalArgumentException.class, () -> new MatchResult(
                UUID.randomUUID(), UUID.randomUUID(), GameKey.ARENA,
                Instant.parse("2026-08-22T12:00:00Z"),
                MatchOutcome.VICTORY, "ELIMINATION",
                Map.of(
                        UUID.randomUUID(), new PlayerResult(1, true, false),
                        UUID.randomUUID(), new PlayerResult(1, true, false))));

        assertThrows(IllegalArgumentException.class, () -> new MatchResult(
                UUID.randomUUID(), UUID.randomUUID(), GameKey.ARENA,
                Instant.parse("2026-08-22T12:00:00Z"),
                MatchOutcome.ABORTED, "SERVER_STOP",
                Map.of(UUID.randomUUID(), new PlayerResult(1, false, false))));
    }

    private static MatchResult result(UUID matchId, GameKey game) {
        return result(UUID.randomUUID(), matchId, game);
    }

    private static MatchResult result(UUID resultId, UUID matchId, GameKey game) {
        return new MatchResult(
                resultId,
                matchId,
                game,
                Instant.parse("2026-08-22T12:00:00Z"),
                MatchOutcome.VICTORY,
                "COMPLETED",
                Map.of(
                        UUID.randomUUID(), new PlayerResult(1, true, false),
                        UUID.randomUUID(), new PlayerResult(2, false, false)));
    }
}
