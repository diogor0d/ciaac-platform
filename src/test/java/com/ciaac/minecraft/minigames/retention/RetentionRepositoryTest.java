package com.ciaac.minecraft.minigames.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetentionRepositoryTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final Instant SAMPLE = Instant.parse("2026-09-15T12:00:00Z");

    @Test void inMemoryTransactionsDiscardMutationsWhenWorkFailsAndPublishCopiesOnSuccess() {
        InMemoryRetentionRepository repository = new InMemoryRetentionRepository();
        assertThrows(IllegalStateException.class, () -> repository.transaction(PLAYER, ledger -> {
            ledger.select("titulo", "uncommitted");
            throw new IllegalStateException("failed work");
        }));
        assertTrue(repository.findLedger(PLAYER).isEmpty());

        PlayerRetentionLedger returned = repository.transaction(PLAYER, ledger -> {
            ledger.select("titulo", "sequencia-um");
            ledger.day(SAMPLE.atZone(LisbonSeasonCalendar.LISBON).toLocalDate()).addMinute(SAMPLE);
            ledger.season("season").addPoints(5);
            return ledger;
        });
        returned.season("season").addPoints(20);
        PlayerRetentionLedger detached = repository.findLedger(PLAYER).orElseThrow();
        detached.select("titulo", "changed-copy");
        detached.day(SAMPLE.atZone(LisbonSeasonCalendar.LISBON).toLocalDate()).addMinute(SAMPLE.plusSeconds(60));
        detached.season("season").addPoints(99);
        repository.allLedgers().getFirst().season("season").addPoints(99);

        PlayerRetentionLedger current = repository.findLedger(PLAYER).orElseThrow();
        assertEquals("sequencia-um", current.selections().get("titulo"));
        assertEquals(1, current.dayView(SAMPLE.atZone(LisbonSeasonCalendar.LISBON).toLocalDate()).sampledMinutes());
        assertEquals(Set.of(SAMPLE), current.dayView(SAMPLE.atZone(LisbonSeasonCalendar.LISBON).toLocalDate()).samples());
        assertEquals(5, current.seasonView("season").points());
    }
}
