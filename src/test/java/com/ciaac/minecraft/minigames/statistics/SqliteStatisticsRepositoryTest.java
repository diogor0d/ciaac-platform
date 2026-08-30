package com.ciaac.minecraft.minigames.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import com.ciaac.minecraft.minigames.persistence.SqliteStatisticsRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteStatisticsRepositoryTest {
    private static final Instant START = Instant.parse("2026-08-24T12:00:00Z");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversRankedScopesInStableOrderAfterRestartSafePersistence() {
        try (SqliteDatabase database = new SqliteDatabase(temporaryDirectory, Path.of("statistics.sqlite"))) {
            SqliteStatisticsRepository repository = new SqliteStatisticsRepository(database);
            repository.record(result("z-rules", "2v2", 100L));
            repository.record(result("a-rules", "3v3", 7L));
            repository.record(result("a-rules", "1v1", 5L));

            assertEquals(List.of(
                    new LeaderboardScope(GameKey.ARENA, "a-rules", "1v1", "wins"),
                    new LeaderboardScope(GameKey.ARENA, "a-rules", "3v3", "wins"),
                    new LeaderboardScope(GameKey.ARENA, "z-rules", "2v2", "wins")),
                    repository.discoverScopes(GameKey.ARENA, "WINS"));
        }
    }

    private static MatchResult result(String ruleset, String mode, long wins) {
        return new MatchResult(
                UUID.randomUUID(), UUID.randomUUID(), GameKey.ARENA,
                ruleset, mode, "season-1", START, START.plusSeconds(wins),
                MatchOutcome.VICTORY, "completed",
                Map.of(PLAYER, PlayerResult.withMetrics(1, true, false, "wins", wins)));
    }
}
