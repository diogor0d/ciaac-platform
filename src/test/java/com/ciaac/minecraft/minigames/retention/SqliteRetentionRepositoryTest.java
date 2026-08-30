package com.ciaac.minecraft.minigames.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteRetentionRepositoryTest {
    @TempDir Path temporaryDirectory;

    @Test void persistsIdempotentCreditsAndRebuildsMutableStateAfterRestart() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID connection = UUID.fromString("00000000-0000-0000-0000-000000000302");
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        try (SqliteDatabase database = new SqliteDatabase(temporaryDirectory, Path.of("platform.sqlite"))) {
            PassportService service = service(new SqliteRetentionRepository(database));
            service.qualifyJoin(player, connection, now.minus(Duration.ofMinutes(10)), now);
            assertEquals(1, service.passport(player, now).points());
        }
        try (SqliteDatabase database = new SqliteDatabase(temporaryDirectory, Path.of("platform.sqlite"))) {
            PassportService service = service(new SqliteRetentionRepository(database));
            assertEquals(1, service.passport(player, now).points());
            service.qualifyJoin(player, connection, now.minus(Duration.ofMinutes(10)), now);
            assertEquals(1, service.passport(player, now).points());
        }
    }

    private static PassportService service(RetentionRepository repository) {
        RetentionConfiguration configuration = new RetentionConfiguration(true, LisbonSeasonCalendar.LISBON,
                LisbonSeasonCalendar.ANCHOR, 3, 14, Duration.ofMinutes(10), 15, 3, 12,
                false, false, false, List.of());
        return new PassportService(configuration, repository, List.of(), List.of());
    }
}
