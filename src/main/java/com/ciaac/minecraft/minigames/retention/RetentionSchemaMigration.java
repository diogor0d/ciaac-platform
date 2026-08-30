package com.ciaac.minecraft.minigames.retention;

import java.util.List;

/** SQL migration contract for the parent-owned SQLite bootstrap: v5 -> v6 in one transaction. */
public final class RetentionSchemaMigration {
    public static final int FROM_VERSION = 5;
    public static final int TO_VERSION = 6;
    private RetentionSchemaMigration() {}
    public static List<String> statements() {
        return List.of(
                "CREATE TABLE mg_retention_event (event_id TEXT PRIMARY KEY, source_id TEXT NOT NULL UNIQUE, player_id TEXT NOT NULL, occurred_at TEXT NOT NULL, local_date TEXT NOT NULL, kind TEXT NOT NULL, season_id TEXT NOT NULL, points INTEGER NOT NULL CHECK(points BETWEEN 0 AND 25), anonymized_at TEXT NULL)",
                "CREATE INDEX mg_retention_event_player_date ON mg_retention_event(player_id, local_date)",
                "CREATE TABLE mg_retention_projection (player_id TEXT NOT NULL, season_id TEXT NOT NULL, current_join_streak INTEGER NOT NULL, longest_join_streak INTEGER NOT NULL, last_join_date TEXT NULL, active_days INTEGER NOT NULL, weekly_objectives INTEGER NOT NULL, season_milestones INTEGER NOT NULL, points INTEGER NOT NULL, join_freeze_available INTEGER NOT NULL CHECK(join_freeze_available IN (0, 1)), updated_at TEXT NOT NULL, PRIMARY KEY(player_id, season_id))",
                "CREATE TABLE mg_retention_daily (player_id TEXT NOT NULL, local_date TEXT NOT NULL, join_qualified INTEGER NOT NULL CHECK(join_qualified IN (0, 1)), active_qualified INTEGER NOT NULL CHECK(active_qualified IN (0, 1)), sampled_minutes INTEGER NOT NULL CHECK(sampled_minutes BETWEEN 0 AND 15), PRIMARY KEY(player_id, local_date))",
                "CREATE TABLE mg_retention_weekly (player_id TEXT NOT NULL, season_id TEXT NOT NULL, week_starts_on TEXT NOT NULL, PRIMARY KEY(player_id, season_id, week_starts_on))",
                "CREATE TABLE mg_retention_entitlement (player_id TEXT NOT NULL, season_id TEXT NOT NULL, reward_id TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('EARNED','GRANTING','GRANTED','DELIVERY_PENDING','MANUAL_REVIEW')), earned_at TEXT NOT NULL, updated_at TEXT NOT NULL, operation_id TEXT NULL UNIQUE, detail_code TEXT NOT NULL, PRIMARY KEY(player_id, season_id, reward_id))",
                "CREATE TABLE mg_retention_selection (player_id TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('titulo','distintivo','particulas')), reward_id TEXT NOT NULL, PRIMARY KEY(player_id, kind))",
                "CREATE TABLE mg_retention_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                "PRAGMA user_version = 6");
    }
}
