package com.ciaac.minecraft.minigames.anvildodge;

import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import java.time.Duration;
import java.util.Objects;

public record AnvilDodgeConfig(int minimumPlayers, int maximumPlayers, int waves,
                               Duration warning, Duration waveDuration, long seed,
                               String rulesetRevision, IsolationPolicy isolation,
                               int floorCells, int hazardsPerWaveStart,
                               int hazardsPerWaveIncrement) {
    public AnvilDodgeConfig {
        if (minimumPlayers < 1 || maximumPlayers < minimumPlayers || maximumPlayers > 64)
            throw new IllegalArgumentException("player bounds are invalid");
        if (waves < 1 || waves > 10_000) throw new IllegalArgumentException("waves are invalid");
        positive(warning, "warning"); positive(waveDuration, "waveDuration");
        if (warning.compareTo(waveDuration) >= 0) throw new IllegalArgumentException("warning must be shorter than waveDuration");
        rulesetRevision = bounded(rulesetRevision, "rulesetRevision");
        isolation = Objects.requireNonNull(isolation, "isolation");
        if (floorCells < 1 || floorCells > 4_096) throw new IllegalArgumentException("floorCells is invalid");
        if (hazardsPerWaveStart < 1 || hazardsPerWaveStart > 4_096
                || hazardsPerWaveIncrement < 0 || hazardsPerWaveIncrement > 4_096) {
            throw new IllegalArgumentException("hazard progression is invalid");
        }
    }
    public AnvilDodgeConfig(int min, int max, int waves, Duration warning, Duration waveDuration, long seed, String revision) {
        this(min, max, waves, warning, waveDuration, seed, revision,
                IsolationPolicy.strictNoProgress(), 64, 1, 1);
    }
    public AnvilDodgeConfig(int min, int max, int waves, Duration warning, Duration waveDuration,
                            long seed, String revision, IsolationPolicy isolation) {
        this(min, max, waves, warning, waveDuration, seed, revision, isolation, 64, 1, 1);
    }
    private static void positive(Duration d, String n) { if (d == null || d.isNegative() || d.isZero()) throw new IllegalArgumentException(n + " must be positive"); }
    static String bounded(String v, String n) { Objects.requireNonNull(v, n); v = v.trim(); if (!v.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}")) throw new IllegalArgumentException(n + " is invalid"); return v; }
}
