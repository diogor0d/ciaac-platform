package com.ciaac.minecraft.minigames.hotpotato;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class HotPotatoGameTest {
    private static final UUID MATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_MATCH = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID PLAYER_C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final Instant START = Instant.parse("2026-08-22T12:00:00Z");

    @Test
    void queueLocksBeforeEntryAndBoundsRoster() {
        HotPotatoGame game = newGame(new FirstPlayerRandom());
        game.join(PLAYER_A, op(3));
        game.join(PLAYER_B, op(4));
        assertThrows(IllegalStateException.class, () -> game.join(PLAYER_C, op(5)));

        game.beginCountdown(op(6));
        assertThrows(IllegalStateException.class, () -> game.join(PLAYER_C, op(7)));
        game.lockEntry(op(8));
        assertEquals(Set.of(PLAYER_A, PLAYER_B), game.livePlayers());
    }

    @Test
    void seededRandomSelectsAnInjectableInitialCarrier() {
        HotPotatoGame game = newGame(new FirstPlayerRandom());
        game.join(PLAYER_A, op(3));
        game.join(PLAYER_B, op(4));
        game.start(START, op(8));
        assertEquals(PLAYER_A, game.carrier().orElseThrow());
        assertEquals(START.plusSeconds(5), game.fuseDeadline().orElseThrow());
    }

    @Test
    void onlyCurrentCarrierCanPassToAnotherLivePlayer() {
        HotPotatoGame game = activeGame();
        assertThrows(IllegalStateException.class,
                () -> game.pass(PLAYER_B, PLAYER_C, START.plusSeconds(1), op(9)));
        assertThrows(IllegalArgumentException.class,
                () -> game.pass(PLAYER_A, PLAYER_A, START.plusSeconds(1), op(10)));
        assertThrows(IllegalStateException.class,
                () -> game.pass(PLAYER_A, UUID.randomUUID(), START.plusSeconds(1), op(11)));

        game.pass(PLAYER_A, PLAYER_B, START.plusSeconds(1), op(12));
        assertEquals(PLAYER_B, game.carrier().orElseThrow());
    }

    @Test
    void immediateReturnIsRejectedUntilCooldownExpires() {
        HotPotatoGame game = activeGame();
        game.pass(PLAYER_A, PLAYER_B, START.plusSeconds(1), op(9));
        assertThrows(IllegalStateException.class,
                () -> game.pass(PLAYER_B, PLAYER_A, START.plusMillis(1500), op(10)));

        game.pass(PLAYER_B, PLAYER_A, START.plusSeconds(2), op(11));
        assertEquals(PLAYER_A, game.carrier().orElseThrow());
    }

    @Test
    void fuseExpiryEliminatesCarrierAndChoosesNextCarrier() {
        HotPotatoGame game = activeGame();
        game.expireFuse(START.plusSeconds(5), op(9));
        assertEquals(HotPotatoPhase.RUNNING, game.phase());
        assertTrue(game.livePlayers().size() == 2);
        assertNotEquals(PLAYER_A, game.carrier().orElseThrow());
        assertEquals(START.plusSeconds(10), game.fuseDeadline().orElseThrow());
    }

    @Test
    void suddenDeathUsesItsOwnFuseAndStillAdvancesOnExpiry() {
        HotPotatoGame game = activeGame();
        game.enterSuddenDeath(START.plusSeconds(5), op(9));
        assertEquals(HotPotatoPhase.SUDDEN_DEATH, game.phase());
        assertEquals(START.plusSeconds(7), game.fuseDeadline().orElseThrow());

        game.expireFuse(START.plusSeconds(7), op(10));
        assertEquals(HotPotatoPhase.SUDDEN_DEATH, game.phase());
        assertEquals(START.plusSeconds(9), game.fuseDeadline().orElseThrow());
    }

    @Test
    void lastSurvivorProducesImmutableResultAndClosesAfterRestore() {
        HotPotatoGame game = newGame(new FirstPlayerRandom());
        game.join(PLAYER_A, op(3));
        game.join(PLAYER_B, op(4));
        game.beginCountdown(op(5));
        game.lockEntry(op(6));
        game.start(START, op(7));
        game.expireFuse(START.plusSeconds(5), op(8));

        assertEquals(HotPotatoPhase.FINISHING, game.phase());
        MatchResult result = game.result().orElseThrow();
        assertEquals(MATCH_ID, result.matchId());
        assertEquals(PLAYER_B, result.winner());
        assertThrows(UnsupportedOperationException.class, () -> result.participants().clear());

        game.beginRestore(op(9));
        game.completeRestore(op(10));
        assertEquals(HotPotatoPhase.CLOSED, game.phase());
        assertEquals(result, game.result().orElseThrow());
    }

    @Test
    void illegalTransitionsAreGuarded() {
        HotPotatoGame game = new HotPotatoGame(
                MATCH_ID, 2, 3, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(1),
                new FirstPlayerRandom());
        assertThrows(IllegalStateException.class, () -> game.start(START, op(1)));
        game.enable(op(1));
        assertThrows(IllegalStateException.class, () -> game.lockEntry(op(2)));
        assertThrows(IllegalStateException.class, () -> game.openQueue(op(1)));
    }

    @Test
    void duplicateAndStaleOperationsAreRejected() {
        HotPotatoGame game = new HotPotatoGame(
                MATCH_ID, 2, 3, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(1),
                new FirstPlayerRandom());
        OperationId enable = op(1);
        game.enable(enable);
        assertThrows(HotPotatoGame.DuplicateOperationException.class, () -> game.enable(enable));
        assertThrows(HotPotatoGame.StaleOperationException.class,
                () -> game.openQueue(new OperationId(OTHER_MATCH, 2)));
        // The exact replay is a duplicate even though the phase has advanced.
        assertThrows(HotPotatoGame.DuplicateOperationException.class,
                () -> game.openQueue(op(1)));
    }

    @Test
    void interruptedMatchCanRecoverWithoutAStaleWinner() {
        HotPotatoGame game = activeGame();
        game.recover(op(9));
        assertEquals(HotPotatoPhase.RECOVERING, game.phase());
        assertTrue(game.livePlayers().isEmpty());
        game.beginRestore(op(10));
        game.completeRestore(op(11));
        assertEquals(HotPotatoPhase.CLOSED, game.phase());
        assertTrue(game.result().isEmpty());
    }

    @Test
    void boundedPassIntentRejectsRangeAndCancelHasNoWinner() {
        HotPotatoConfig config = new HotPotatoConfig(2, 3, Duration.ofSeconds(5),
                Duration.ofSeconds(2), Duration.ofSeconds(1), 3.0, Duration.ofMinutes(5), "v1");
        HotPotatoGame game = new HotPotatoGame(MATCH_ID, config, new FirstPlayerRandom());
        game.enable(op(1)); game.openQueue(op(2)); game.join(PLAYER_A, op(3)); game.join(PLAYER_B, op(4)); game.start(START, op(5));
        assertThrows(IllegalStateException.class, () -> game.pass(new PassIntent(PLAYER_A, PLAYER_B, 4.0, true), START.plusSeconds(1), op(6)));
        game.cancel(START.plusSeconds(1), "operator_stop", op(7));
        assertEquals("CANCELLED", game.outcome().orElseThrow().kind());
        assertTrue(game.result().isEmpty());
        assertEquals("v1", game.metrics().rulesetRevision());
    }

    @Test
    void waitingRecoveryClearsQueuedPlayersAndClosesWithoutWinner() {
        HotPotatoGame game = newGame(new FirstPlayerRandom());
        game.join(PLAYER_A, op(3));
        game.join(PLAYER_B, op(4));

        game.recover(op(5));
        assertEquals(HotPotatoPhase.RECOVERING, game.phase());
        assertTrue(game.roster().isEmpty());
        assertTrue(game.livePlayers().isEmpty());
        assertTrue(game.result().isEmpty());
        assertThrows(HotPotatoGame.DuplicateOperationException.class, () -> game.recover(op(5)));

        game.beginRestore(op(6));
        game.completeRestore(op(7));
        assertEquals(HotPotatoPhase.CLOSED, game.phase());
        assertTrue(game.result().isEmpty());
    }

    @Test
    void countdownRecoveryClearsFrozenRosterAndRejectsStaleOperations() {
        HotPotatoGame game = newGame(new FirstPlayerRandom());
        game.join(PLAYER_A, op(3));
        game.join(PLAYER_B, op(4));
        game.beginCountdown(op(5));

        assertThrows(HotPotatoGame.StaleOperationException.class,
                () -> game.recover(new OperationId(OTHER_MATCH, 6)));
        game.recover(op(6));
        assertEquals(HotPotatoPhase.RECOVERING, game.phase());
        assertTrue(game.roster().isEmpty());
        assertTrue(game.livePlayers().isEmpty());
        assertTrue(game.result().isEmpty());

        game.beginRestore(op(7));
        game.completeRestore(op(8));
        assertEquals(HotPotatoPhase.CLOSED, game.phase());
    }

    private static HotPotatoGame newGame(RandomGenerator random) {
        HotPotatoGame game = new HotPotatoGame(
                MATCH_ID, 2, 2, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(1), random);
        game.enable(op(1));
        game.openQueue(op(2));
        return game;
    }

    private static HotPotatoGame activeGame() {
        HotPotatoGame game = new HotPotatoGame(
                MATCH_ID, 2, 3, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(1),
                new FirstPlayerRandom());
        game.enable(op(1));
        game.openQueue(op(2));
        game.join(PLAYER_A, op(3));
        game.join(PLAYER_B, op(4));
        game.join(PLAYER_C, op(5));
        game.beginCountdown(op(6));
        game.lockEntry(op(7));
        game.start(START, op(8));
        return game;
    }

    private static OperationId op(long sequence) {
        return new OperationId(MATCH_ID, sequence);
    }

    private static final class FirstPlayerRandom implements RandomGenerator {
        @Override
        public long nextLong() {
            return 0;
        }
    }
}
