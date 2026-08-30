package com.ciaac.minecraft.minigames.buildbattle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BuildBattleMatchTest {
    private static final UUID MATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID PLAYER_C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final BuildBattlePlot PLOT_A = new BuildBattlePlot("plot-a");
    private static final BuildBattlePlot PLOT_B = new BuildBattlePlot("plot-b");
    private static final BuildBattlePlot PLOT_C = new BuildBattlePlot("plot-c");

    @Test
    void oneInstanceAndRosterBoundsAreEnforced() {
        BuildBattleMatch match = newMatch(2, 2);
        match.openWaiting(MATCH_ID);
        assertEquals(true, match.join(PLAYER_A));
        assertEquals(true, match.join(PLAYER_B));
        assertEquals(false, match.join(PLAYER_C));
        assertThrows(IllegalStateException.class, () -> match.openWaiting());
    }

    @Test
    void rejectedJoinDoesNotConsumeItsOperationId() {
        BuildBattleMatch match = newMatch(2, 2);
        match.openWaiting(MATCH_ID);
        match.join(PLAYER_A, new BuildBattleOperationId(MATCH_ID, 1));
        match.join(PLAYER_B, new BuildBattleOperationId(MATCH_ID, 2));

        BuildBattleOperationId rejected = new BuildBattleOperationId(MATCH_ID, 3);
        assertEquals(false, match.join(PLAYER_C, rejected));
        match.leave(PLAYER_B, new BuildBattleOperationId(MATCH_ID, 4));

        assertEquals(true, match.join(PLAYER_C, rejected));
    }

    @Test
    void countdownFreezesRosterAndLeaveIsLocked() {
        BuildBattleMatch match = newMatch(2, 3);
        match.openWaiting(MATCH_ID);
        match.join(PLAYER_A);
        match.join(PLAYER_B);
        match.beginCountdown();

        assertThrows(IllegalStateException.class, () -> match.join(PLAYER_C));
        assertThrows(IllegalStateException.class, () -> match.leave(PLAYER_A));
        assertThrows(IllegalStateException.class, match::beginCountdown);
    }

    @Test
    void plotsAreAssignedDeterministicallyAndExposeOwnership() {
        BuildBattleMatch match = newMatch(2, 3);
        match.openWaiting(MATCH_ID);
        // Join order is intentionally different from UUID order.
        match.join(PLAYER_B);
        match.join(PLAYER_A);
        match.join(PLAYER_C);
        match.beginCountdown();
        match.beginBuilding();

        assertEquals(List.of(
                new BuildBattlePlotAssignment(PLOT_A, PLAYER_A),
                new BuildBattlePlotAssignment(PLOT_B, PLAYER_B),
                new BuildBattlePlotAssignment(PLOT_C, PLAYER_C)), match.assignments());
        assertEquals(PLAYER_B, match.ownerOf(PLOT_B));
        assertEquals(PLOT_C, match.plotOf(PLAYER_C));
    }

    @Test
    void ballotIntegrityRejectsSelfDuplicateIneligibleAndLateVotes() {
        BuildBattleMatch match = readyForVoting();

        assertThrows(IllegalArgumentException.class, () -> match.castVote(PLAYER_A, PLOT_A, 5));
        assertThrows(IllegalArgumentException.class, () -> match.castVote(OUTSIDER, PLOT_A, 5));
        assertThrows(IllegalArgumentException.class, () -> match.castVote(PLAYER_A, PLOT_B, 0));

        match.castVote(PLAYER_A, PLOT_B, 5);
        assertThrows(IllegalArgumentException.class, () -> match.castVote(PLAYER_A, PLOT_B, 4));
        assertThrows(IllegalStateException.class, match::beginResults);
        match.castVote(PLAYER_B, PLOT_A, 5);
        match.beginResults();
        assertThrows(IllegalStateException.class, () -> match.castVote(PLAYER_B, PLOT_A, 5));
    }

    @Test
    void rejectedVoteDoesNotConsumeItsOperationId() {
        BuildBattleMatch match = readyForVoting();
        BuildBattleOperationId retry = new BuildBattleOperationId(MATCH_ID, 1);

        assertThrows(IllegalArgumentException.class,
                () -> match.castVote(OUTSIDER, PLOT_B, 5, retry));
        match.castVote(PLAYER_A, PLOT_B, 5, retry);

        assertEquals(1, match.ballotCount());
        assertThrows(IllegalStateException.class,
                () -> match.castVote(PLAYER_A, PLOT_B, 5, retry));
    }

    @Test
    void zeroBallotsCannotEnterResults() {
        BuildBattleMatch match = readyForVoting();

        assertThrows(IllegalStateException.class, match::beginResults);
        assertEquals(BuildBattlePhase.VOTING, match.phase());
    }

    @Test
    void partialBallotsCannotEnterResults() {
        BuildBattleMatch match = readyForVoting(3);
        match.castVote(PLAYER_A, PLOT_B, 5);

        assertThrows(IllegalStateException.class, match::beginResults);
        assertEquals(BuildBattlePhase.VOTING, match.phase());
    }

    @Test
    void unequalVoteCountsCannotEnterResults() {
        BuildBattleMatch match = readyForVoting(3);
        match.castVote(PLAYER_A, PLOT_B, 5);
        match.castVote(PLAYER_A, PLOT_C, 5);
        match.castVote(PLAYER_B, PLOT_A, 5);
        match.castVote(PLAYER_B, PLOT_C, 5);
        match.castVote(PLAYER_C, PLOT_A, 5);

        assertThrows(IllegalStateException.class, match::beginResults);
        assertEquals(BuildBattlePhase.VOTING, match.phase());
    }

    @Test
    void fullBallotsCanEnterResultsAndRecordEveryScore() {
        BuildBattleMatch match = readyForVoting(3);
        castFullBallots(match);

        match.beginResults();
        BuildBattleResult result = match.finalizeResult();

        assertEquals(PLOT_A, result.winner());
        assertEquals(BuildBattleTiePolicy.AVERAGE_THEN_TOTAL_THEN_PLOT_ID, result.tiePolicy());
        assertEquals(2, result.scores().get(PLOT_A).votes());
        assertEquals(2, result.scores().get(PLOT_B).votes());
        assertEquals(2, result.scores().get(PLOT_C).votes());
    }

    @Test
    void equalScoresUseStablePlotIdTieBreak() {
        BuildBattleMatch match = readyForVoting();
        match.castVote(PLAYER_A, PLOT_B, 5);
        match.castVote(PLAYER_B, PLOT_A, 5);
        match.beginResults();

        BuildBattleResult result = match.finalizeResult();
        assertEquals(PLOT_A, result.winner());
        assertEquals(BuildBattleTiePolicy.AVERAGE_THEN_TOTAL_THEN_PLOT_ID, result.tiePolicy());
    }

    @Test
    void resultFinalizationIsIdempotentAndImmutable() {
        BuildBattleMatch match = readyForVoting();
        match.castVote(PLAYER_A, PLOT_B, 4);
        match.castVote(PLAYER_B, PLOT_A, 5);
        match.beginResults();

        BuildBattleResult first = match.finalizeResult();
        BuildBattleResult second = match.finalizeResult();
        assertSame(first, second);
        assertThrows(UnsupportedOperationException.class, () -> first.assignments().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.scores().clear());
    }

    @Test
    void resetRequiresFinalizedResultsAndRecoveryIsGuarded() {
        BuildBattleMatch match = newMatch(2, 3);
        assertThrows(IllegalStateException.class, match::beginRecovery);
        match.openWaiting(MATCH_ID);
        match.join(PLAYER_A);
        match.join(PLAYER_B);
        match.beginCountdown();
        match.beginBuilding();
        assertThrows(IllegalStateException.class, match::beginResetting);

        match.beginRecovery();
        assertEquals(BuildBattlePhase.RECOVERING, match.phase());
        match.completeRecovery();
        assertEquals(BuildBattlePhase.IDLE, match.phase());
        assertThrows(IllegalStateException.class, match::completeRecovery);

        match.close();
        assertThrows(IllegalStateException.class, match::openWaiting);
        assertThrows(IllegalStateException.class, match::beginRecovery);
    }

    private static BuildBattleMatch readyForVoting() {
        return readyForVoting(2);
    }

    private static BuildBattleMatch readyForVoting(int playerCount) {
        BuildBattleMatch match = newMatch(2, playerCount);
        match.openWaiting(MATCH_ID);
        match.join(PLAYER_A);
        match.join(PLAYER_B);
        if (playerCount == 3) {
            match.join(PLAYER_C);
        }
        match.beginCountdown();
        match.beginBuilding();
        match.beginVoting();
        return match;
    }

    private static void castFullBallots(BuildBattleMatch match) {
        match.castVote(PLAYER_A, PLOT_B, 5);
        match.castVote(PLAYER_A, PLOT_C, 5);
        match.castVote(PLAYER_B, PLOT_A, 5);
        match.castVote(PLAYER_B, PLOT_C, 5);
        match.castVote(PLAYER_C, PLOT_A, 5);
        match.castVote(PLAYER_C, PLOT_B, 5);
    }

    private static BuildBattleMatch newMatch(int minimumPlayers, int maximumPlayers) {
        BuildBattleConfig config = new BuildBattleConfig(
                minimumPlayers, maximumPlayers, 1, 5,
                new BuildBattleTheme("village", "Aldeia"));
        return new BuildBattleMatch(config, List.of(PLOT_C, PLOT_A, PLOT_B));
    }
}
