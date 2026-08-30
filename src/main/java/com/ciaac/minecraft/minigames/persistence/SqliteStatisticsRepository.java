package com.ciaac.minecraft.minigames.persistence;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.statistics.InMemoryStatisticsRepository;
import com.ciaac.minecraft.minigames.statistics.LeaderboardEntry;
import com.ciaac.minecraft.minigames.statistics.LeaderboardQuery;
import com.ciaac.minecraft.minigames.statistics.LeaderboardScope;
import com.ciaac.minecraft.minigames.statistics.MatchOutcome;
import com.ciaac.minecraft.minigames.statistics.MatchResult;
import com.ciaac.minecraft.minigames.statistics.PlayerResult;
import com.ciaac.minecraft.minigames.statistics.StatisticsRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Restart-safe immutable result store with reference-projection semantics. */
public final class SqliteStatisticsRepository implements StatisticsRepository {
    private final SqliteDatabase database;

    public SqliteStatisticsRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public boolean record(MatchResult result) {
        Objects.requireNonNull(result, "result");
        return database.transaction(connection -> {
            MatchResult existingByResult = loadBy(connection, "result_id", result.resultId());
            if (existingByResult != null) {
                if (!existingByResult.equals(result)) {
                    throw new IllegalStateException("Conflicting replay for result " + result.resultId());
                }
                return false;
            }
            MatchResult existingByMatch = loadBy(connection, "match_id", result.matchId());
            if (existingByMatch != null) {
                throw new IllegalStateException(
                        "Match " + result.matchId() + " already has result " + existingByMatch.resultId());
            }
            insertResult(connection, result);
            return true;
        });
    }

    @Override
    public Optional<MatchResult> findByResultId(UUID resultId) {
        Objects.requireNonNull(resultId, "resultId");
        return database.read(connection -> Optional.ofNullable(loadBy(connection, "result_id", resultId)));
    }

    @Override
    public Optional<MatchResult> findByMatchId(UUID matchId) {
        Objects.requireNonNull(matchId, "matchId");
        return database.read(connection -> Optional.ofNullable(loadBy(connection, "match_id", matchId)));
    }

    @Override
    public List<LeaderboardScope> discoverScopes(GameKey game, String metric) {
        return projection().discoverScopes(
                Objects.requireNonNull(game, "game"),
                Objects.requireNonNull(metric, "metric"));
    }

    @Override
    public List<LeaderboardEntry> leaderboard(LeaderboardQuery query) {
        return projection().leaderboard(Objects.requireNonNull(query, "query"));
    }

    @Override
    public Optional<LeaderboardEntry> position(LeaderboardQuery query, UUID playerId) {
        return projection().position(
                Objects.requireNonNull(query, "query"),
                Objects.requireNonNull(playerId, "playerId"));
    }

    private InMemoryStatisticsRepository projection() {
        return database.read(connection -> {
            InMemoryStatisticsRepository projection = new InMemoryStatisticsRepository();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT result_id FROM mg_match_result ORDER BY finished_at, result_id");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    MatchResult stored = loadBy(
                            connection, "result_id", UUID.fromString(result.getString(1)));
                    projection.record(stored);
                }
            }
            return projection;
        });
    }

    private static void insertResult(Connection connection, MatchResult result) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO mg_match_result (
                    result_id, match_id, game_id, ruleset, mode, season,
                    started_at, finished_at, outcome, reason_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, result.resultId().toString());
            insert.setString(2, result.matchId().toString());
            insert.setString(3, result.game().id());
            insert.setString(4, result.ruleset());
            insert.setString(5, result.mode());
            insert.setString(6, result.season());
            insert.setString(7, result.startedAt().toString());
            insert.setString(8, result.finishedAt().toString());
            insert.setString(9, result.outcome().name());
            insert.setString(10, result.reasonCode());
            insert.executeUpdate();
        }
        for (Map.Entry<UUID, PlayerResult> entry : result.players().entrySet()) {
            UUID playerId = entry.getKey();
            PlayerResult player = entry.getValue();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO mg_player_result (
                        result_id, player_id, placement, winner, forfeit, team_id
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, result.resultId().toString());
                insert.setString(2, playerId.toString());
                insert.setInt(3, player.placement());
                insert.setInt(4, player.winner() ? 1 : 0);
                insert.setInt(5, player.forfeit() ? 1 : 0);
                if (player.teamId().isPresent()) {
                    insert.setString(6, player.teamId().orElseThrow());
                } else {
                    insert.setNull(6, java.sql.Types.VARCHAR);
                }
                insert.executeUpdate();
            }
            for (Map.Entry<String, Long> metric : player.metrics().entrySet()) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO mg_player_metric (
                            result_id, player_id, metric_key, metric_value
                        ) VALUES (?, ?, ?, ?)
                        """)) {
                    insert.setString(1, result.resultId().toString());
                    insert.setString(2, playerId.toString());
                    insert.setString(3, metric.getKey());
                    insert.setLong(4, metric.getValue());
                    insert.executeUpdate();
                }
            }
        }
    }

    private static MatchResult loadBy(Connection connection, String column, UUID id) throws SQLException {
        if (!column.equals("result_id") && !column.equals("match_id")) {
            throw new IllegalArgumentException("Unsupported immutable result lookup column");
        }
        UUID resultId;
        UUID matchId;
        GameKey game;
        String ruleset;
        String mode;
        String season;
        Instant startedAt;
        Instant finishedAt;
        MatchOutcome outcome;
        String reason;
        String resultLookupSql = """
                SELECT result_id, match_id, game_id, ruleset, mode, season,
                       started_at, finished_at, outcome, reason_code
                FROM mg_match_result
                WHERE %s = ?
                """.formatted(column);
        try (PreparedStatement statement = connection.prepareStatement(resultLookupSql)) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                resultId = UUID.fromString(result.getString(1));
                matchId = UUID.fromString(result.getString(2));
                game = GameKey.fromId(result.getString(3))
                        .orElseThrow(() -> new PersistenceFailure("Stored result has an unknown game"));
                ruleset = result.getString(4);
                mode = result.getString(5);
                season = result.getString(6);
                startedAt = Instant.parse(result.getString(7));
                finishedAt = Instant.parse(result.getString(8));
                outcome = MatchOutcome.valueOf(result.getString(9));
                reason = result.getString(10);
            }
        }
        Map<UUID, PlayerResult> players = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id, placement, winner, forfeit, team_id
                FROM mg_player_result WHERE result_id = ? ORDER BY player_id
                """)) {
            statement.setString(1, resultId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID playerId = UUID.fromString(result.getString(1));
                    String team = result.getString(5);
                    players.put(playerId, new PlayerResult(
                            result.getInt(2),
                            result.getInt(3) != 0,
                            result.getInt(4) != 0,
                            Optional.ofNullable(team),
                            loadMetrics(connection, resultId, playerId)));
                }
            }
        }
        return new MatchResult(
                resultId, matchId, game, ruleset, mode, season,
                startedAt, finishedAt, outcome, reason, players);
    }

    private static Map<String, Long> loadMetrics(
            Connection connection, UUID resultId, UUID playerId) throws SQLException {
        Map<String, Long> metrics = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT metric_key, metric_value FROM mg_player_metric
                WHERE result_id = ? AND player_id = ? ORDER BY metric_key
                """)) {
            statement.setString(1, resultId.toString());
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    metrics.put(result.getString(1), result.getLong(2));
                }
            }
        }
        return Map.copyOf(metrics);
    }
}
