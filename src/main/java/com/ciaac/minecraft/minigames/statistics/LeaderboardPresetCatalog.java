package com.ciaac.minecraft.minigames.statistics;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.EnumMap;
import java.util.Map;

/** Public defaults; ruleset- and season-specific boards can be added without changing stored results. */
public final class LeaderboardPresetCatalog {
    private static final Map<GameKey, LeaderboardPreset> PRESETS;
    static {
        EnumMap<GameKey, LeaderboardPreset> values = new EnumMap<>(GameKey.class);
        values.put(GameKey.ARENA, higher(GameKey.ARENA, "wins", "vitórias", MetricAggregation.SUM));
        values.put(GameKey.BUILD_BATTLE, higher(GameKey.BUILD_BATTLE, "wins", "vitórias", MetricAggregation.SUM));
        values.put(GameKey.HOT_POTATO, higher(GameKey.HOT_POTATO, "wins", "vitórias", MetricAggregation.SUM));
        values.put(GameKey.KNOCKBACK_SUMO, higher(GameKey.KNOCKBACK_SUMO, "wins", "vitórias", MetricAggregation.SUM));
        values.put(GameKey.CHECKPOINT_PARKOUR, lower(GameKey.CHECKPOINT_PARKOUR, "time_ms", "melhor tempo", MetricAggregation.MIN));
        values.put(GameKey.ARCHERY_RANGE, higher(GameKey.ARCHERY_RANGE, "score", "melhor pontuação", MetricAggregation.MAX));
        values.put(GameKey.ANVIL_DODGE, higher(GameKey.ANVIL_DODGE, "survival_ms", "maior sobrevivência", MetricAggregation.MAX));
        values.put(GameKey.COLOR_FLOOR, higher(GameKey.COLOR_FLOOR, "wins", "vitórias", MetricAggregation.SUM));
        values.put(GameKey.ELYTRA_RINGS, lower(GameKey.ELYTRA_RINGS, "time_ms", "melhor tempo", MetricAggregation.MIN));
        PRESETS = Map.copyOf(values);
    }

    private LeaderboardPresetCatalog() {}
    public static LeaderboardPreset get(GameKey game) { return PRESETS.get(game); }
    private static LeaderboardPreset higher(GameKey game, String metric, String label, MetricAggregation aggregation) {
        return new LeaderboardPreset(game, metric, label, aggregation, LeaderboardDirection.HIGHER_IS_BETTER);
    }
    private static LeaderboardPreset lower(GameKey game, String metric, String label, MetricAggregation aggregation) {
        return new LeaderboardPreset(game, metric, label, aggregation, LeaderboardDirection.LOWER_IS_BETTER);
    }
}
