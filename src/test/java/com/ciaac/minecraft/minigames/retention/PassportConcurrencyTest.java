package com.ciaac.minecraft.minigames.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class PassportConcurrencyTest {
    @Test void concurrentDailyJoinReplaysProduceOneCredit() throws Exception {
        PassportService service = new PassportService(new RetentionConfiguration(true, LisbonSeasonCalendar.LISBON, LisbonSeasonCalendar.ANCHOR,
                3, 14, Duration.ofMinutes(10), 15, 3, 12, false, false, false, List.of()), new InMemoryRetentionRepository(), List.of(), List.of());
        UUID player = UUID.randomUUID(); Instant now = Instant.parse("2026-09-15T12:00:00Z");
        ExecutorService pool = Executors.newFixedThreadPool(8); CountDownLatch start = new CountDownLatch(1); List<Future<PassportService.QualificationResult>> work = new ArrayList<>();
        for (int i = 0; i < 24; i++) { UUID connection = UUID.randomUUID(); work.add(pool.submit(() -> { start.await(); return service.qualifyJoin(player, connection, now.minus(Duration.ofMinutes(10)), now); })); }
        start.countDown(); int credits = 0; for (Future<PassportService.QualificationResult> result : work) if (result.get().outcome() == PassportService.Outcome.CREDITED) credits++;
        pool.shutdownNow();
        assertEquals(1, credits);
        assertEquals(1, service.passport(player, now).points());
    }
}
