package com.ciaac.minecraft.minigames.retention;

import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** SQLite-backed append-only activity ledger with rebuildable projections. */
public final class SqliteRetentionRepository implements RetentionRepository {
    private final SqliteDatabase database;
    private final Map<UUID, PlayerRetentionLedger> ledgers = new LinkedHashMap<>();

    public SqliteRetentionRepository(SqliteDatabase database) {
        this.database = java.util.Objects.requireNonNull(database, "database");
        this.database.read(connection -> {
            loadAll(connection);
            return null;
        });
    }

    @Override
    public synchronized <T> T transaction(UUID playerId, Function<PlayerRetentionLedger, T> work) {
        PlayerRetentionLedger ledger = ledgers.computeIfAbsent(playerId, PlayerRetentionLedger::new);
        T result = work.apply(ledger);
        database.transaction(connection -> {
            persist(connection, ledger);
            return null;
        });
        return result;
    }

    @Override
    public synchronized List<PlayerRetentionLedger> allLedgers() {
        return List.copyOf(ledgers.values());
    }

    public LocalDate initializeScoringStart(LisbonSeasonCalendar calendar, Instant now) {
        return database.transaction(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT value FROM mg_retention_state WHERE key='scoring_start'")) {
                try (ResultSet row = query.executeQuery()) {
                    if (row.next()) return LocalDate.parse(row.getString(1));
                }
            }
            LocalDate date = calendar.localDate(now);
            SeasonWindow season = calendar.seasonContaining(date);
            LocalDate start = date.isBefore(LisbonSeasonCalendar.ANCHOR) ? LisbonSeasonCalendar.ANCHOR
                    : date.equals(season.startsOn()) ? season.startsOn() : season.endsOnExclusive();
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO mg_retention_state(key, value) VALUES('scoring_start', ?)")) {
                insert.setString(1, start.toString()); insert.executeUpdate();
            }
            return start;
        });
    }

    @Override
    public synchronized void purgeDetailedBefore(Instant cutoff) {
        ledgers.values().forEach(ledger -> ledger.purgeDetailedBefore(cutoff));
        database.transaction(connection -> {
            try (PreparedStatement events = connection.prepareStatement(
                    "DELETE FROM mg_retention_event WHERE occurred_at < ?");
                 PreparedStatement daily = connection.prepareStatement(
                    "DELETE FROM mg_retention_daily WHERE local_date < ?");
                 PreparedStatement weekly = connection.prepareStatement(
                    "DELETE FROM mg_retention_weekly WHERE week_starts_on < ?")) {
                events.setString(1, cutoff.toString()); events.executeUpdate();
                String local = cutoff.atZone(LisbonSeasonCalendar.LISBON).toLocalDate().toString();
                daily.setString(1, local); daily.executeUpdate();
                weekly.setString(1, local); weekly.executeUpdate();
            }
            return null;
        });
    }

    private void loadAll(Connection connection) throws SQLException {
        Set<UUID> players = new HashSet<>();
        for (String table : List.of("mg_retention_event", "mg_retention_projection", "mg_retention_entitlement",
                "mg_retention_selection")) {
            try (var statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT DISTINCT player_id FROM " + table)) {
                while (rows.next()) players.add(UUID.fromString(rows.getString(1)));
            }
        }
        for (UUID player : players) ledgers.put(player, load(connection, player));
    }

    private static PlayerRetentionLedger load(Connection connection, UUID player) throws SQLException {
        PlayerRetentionLedger ledger = new PlayerRetentionLedger(player);
        Map<LocalDate, Set<Instant>> samples = new HashMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT event_id, source_id, occurred_at, local_date, kind, season_id, points
                FROM mg_retention_event WHERE player_id = ? ORDER BY occurred_at, event_id
                """)) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    RetentionEvent event = new RetentionEvent(UUID.fromString(rows.getString(1)),
                            UUID.fromString(rows.getString(2)), player, Instant.parse(rows.getString(3)),
                            LocalDate.parse(rows.getString(4)), RetentionEvent.Kind.valueOf(rows.getString(5)),
                            rows.getString(6), rows.getInt(7));
                    ledger.append(event);
                    if (event.kind() == RetentionEvent.Kind.ACTIVE_MINUTE) {
                        samples.computeIfAbsent(event.localDate(), ignored -> new HashSet<>()).add(event.occurredAt());
                    }
                }
            }
        }
        LocalDate newestJoinDate = null;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT season_id, current_join_streak, longest_join_streak, last_join_date,
                       active_days, weekly_objectives, season_milestones, points, join_freeze_available
                FROM mg_retention_projection WHERE player_id = ?
                """)) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    LocalDate last = rows.getString(4) == null ? null : LocalDate.parse(rows.getString(4));
                    if (last != null && (newestJoinDate == null || last.isAfter(newestJoinDate))) {
                        newestJoinDate = last;
                        ledger.restoreJoinProjection(rows.getInt(2), rows.getInt(3), last);
                    }
                    ledger.season(rows.getString(1)).restore(rows.getInt(8), rows.getInt(5), rows.getInt(6),
                            rows.getInt(7), rows.getInt(9) == 1, Set.of());
                }
            }
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT local_date, join_qualified, active_qualified FROM mg_retention_daily WHERE player_id = ?")) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    LocalDate date = LocalDate.parse(rows.getString(1));
                    ledger.day(date).restore(rows.getInt(2) == 1, rows.getInt(3) == 1,
                            samples.getOrDefault(date, Set.of()));
                }
            }
        }
        Map<String, Set<LocalDate>> weeks = new HashMap<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT season_id, week_starts_on FROM mg_retention_weekly WHERE player_id = ?")) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) weeks.computeIfAbsent(rows.getString(1), ignored -> new HashSet<>())
                        .add(LocalDate.parse(rows.getString(2)));
            }
        }
        for (var entry : weeks.entrySet()) {
            var progress = ledger.season(entry.getKey());
            progress.restore(progress.points(), progress.activeDays(), progress.weeklyObjectives(),
                    progress.seasonMilestones(), progress.joinFreezeAvailable(), entry.getValue());
        }
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT season_id, reward_id, state, earned_at, updated_at, operation_id, detail_code
                FROM mg_retention_entitlement WHERE player_id = ?
                """)) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) ledger.entitlement(new Entitlement(rows.getString(1), rows.getString(2),
                        Entitlement.EntitlementState.valueOf(rows.getString(3)), Instant.parse(rows.getString(4)),
                        Instant.parse(rows.getString(5)), rows.getString(6) == null ? null : UUID.fromString(rows.getString(6)),
                        rows.getString(7)));
            }
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT kind, reward_id FROM mg_retention_selection WHERE player_id = ?")) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) ledger.select(rows.getString(1), rows.getString(2));
            }
        }
        return ledger;
    }

    private static void persist(Connection connection, PlayerRetentionLedger ledger) throws SQLException {
        insertEvents(connection, ledger);
        deletePlayerRows(connection, ledger.playerId(), "mg_retention_selection", "mg_retention_entitlement", "mg_retention_weekly",
                "mg_retention_daily", "mg_retention_projection");
        persistProjections(connection, ledger);
        persistDaily(connection, ledger);
        persistWeeks(connection, ledger);
        persistEntitlements(connection, ledger);
        persistSelections(connection, ledger);
    }

    private static void insertEvents(Connection connection, PlayerRetentionLedger ledger) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO mg_retention_event
                (event_id, source_id, player_id, occurred_at, local_date, kind, season_id, points, anonymized_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)
                """)) {
            for (RetentionEvent event : ledger.events().values()) {
                insert.setString(1, event.eventId().toString()); insert.setString(2, event.sourceId().toString());
                insert.setString(3, event.playerId().toString()); insert.setString(4, event.occurredAt().toString());
                insert.setString(5, event.localDate().toString()); insert.setString(6, event.kind().name());
                insert.setString(7, event.seasonId()); insert.setInt(8, event.points()); insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void persistProjections(Connection connection, PlayerRetentionLedger ledger) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO mg_retention_projection VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var entry : ledger.seasonEntries().entrySet()) {
                var progress = entry.getValue();
                insert.setString(1, ledger.playerId().toString()); insert.setString(2, entry.getKey());
                insert.setInt(3, ledger.currentJoinStreak()); insert.setInt(4, ledger.longestJoinStreak());
                insert.setString(5, ledger.lastJoinDate() == null ? null : ledger.lastJoinDate().toString());
                insert.setInt(6, progress.activeDays()); insert.setInt(7, progress.weeklyObjectives());
                insert.setInt(8, progress.seasonMilestones()); insert.setInt(9, progress.points());
                insert.setInt(10, progress.joinFreezeAvailable() ? 1 : 0); insert.setString(11, Instant.now().toString());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void persistDaily(Connection connection, PlayerRetentionLedger ledger) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO mg_retention_daily VALUES (?, ?, ?, ?, ?)")) {
            for (var entry : ledger.dailyEntries().entrySet()) {
                insert.setString(1, ledger.playerId().toString()); insert.setString(2, entry.getKey().toString());
                insert.setInt(3, entry.getValue().joinQualified() ? 1 : 0);
                insert.setInt(4, entry.getValue().activeQualified() ? 1 : 0);
                insert.setInt(5, entry.getValue().sampledMinutes()); insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void persistWeeks(Connection connection, PlayerRetentionLedger ledger) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO mg_retention_weekly VALUES (?, ?, ?)")) {
            for (var season : ledger.seasonEntries().entrySet()) for (LocalDate week : season.getValue().weeklyWeeks()) {
                insert.setString(1, ledger.playerId().toString()); insert.setString(2, season.getKey());
                insert.setString(3, week.toString()); insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void persistEntitlements(Connection connection, PlayerRetentionLedger ledger) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO mg_retention_entitlement VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Entitlement value : ledger.entitlements().values()) {
                insert.setString(1, ledger.playerId().toString()); insert.setString(2, value.seasonId());
                insert.setString(3, value.rewardId()); insert.setString(4, value.state().name());
                insert.setString(5, value.earnedAt().toString()); insert.setString(6, value.updatedAt().toString());
                insert.setString(7, value.operationId() == null ? null : value.operationId().toString());
                insert.setString(8, value.detailCode()); insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void persistSelections(Connection connection, PlayerRetentionLedger ledger) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO mg_retention_selection VALUES (?, ?, ?)")) {
            for (var selection : ledger.selections().entrySet()) {
                insert.setString(1, ledger.playerId().toString()); insert.setString(2, selection.getKey());
                insert.setString(3, selection.getValue()); insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void deletePlayerRows(Connection connection, UUID player, String... tables) throws SQLException {
        for (String table : tables) try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE player_id = ?")) {
            delete.setString(1, player.toString()); delete.executeUpdate();
        }
    }
}
