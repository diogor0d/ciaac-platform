package com.ciaac.minecraft.minigames.buildbattle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BuildBattleThemeVotingTest {
    private static final UUID MATCH = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void optionsAndPluralityAreDeterministicAndLockTheRoster() {
        BuildBattleTheme first = new BuildBattleTheme("aldeia", "Aldeia");
        BuildBattleTheme second = new BuildBattleTheme("oceano", "Oceano");
        BuildBattleTheme third = new BuildBattleTheme("espaco", "Espaço");
        BuildBattleMatch match = new BuildBattleMatch(
                new BuildBattleConfig(2, 2, 1, 5, first),
                List.of(new BuildBattlePlot("a"), new BuildBattlePlot("b")));
        match.openWaiting(MATCH);
        match.join(PLAYER_A);
        match.join(PLAYER_B);
        match.beginThemeVoting(List.of(first, second, third));

        match.voteTheme(PLAYER_A, second, new BuildBattleOperationId(MATCH, 1));
        match.voteTheme(PLAYER_B, second, new BuildBattleOperationId(MATCH, 2));
        assertThrows(IllegalStateException.class, () -> match.join(UUID.randomUUID()));

        assertEquals(second, match.lockTheme());
        match.beginCountdown();
        assertEquals(second, match.theme());
    }

    @Test
    void seededOptionSelectionIsStableAndBounded() {
        BuildBattleThemePool pool = new BuildBattleThemePool(List.of(
                new BuildBattleTheme("a", "A"),
                new BuildBattleTheme("b", "B"),
                new BuildBattleTheme("c", "C"),
                new BuildBattleTheme("d", "D")));
        assertEquals(pool.options(7L, 3), pool.options(7L, 3));
        assertEquals(3, pool.options(7L, 3).size());
        assertThrows(IllegalArgumentException.class, () -> pool.options(7L, 4));
    }
}
