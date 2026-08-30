package com.ciaac.minecraft.minigames.colorfloor;

import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Immutable ruleset, including the exact colours eligible for each round. */
public record ColorFloorConfig(
        int minimumPlayers,
        int maximumPlayers,
        int rounds,
        Duration reactionWindow,
        long seed,
        String rulesetRevision,
        IsolationPolicy isolation,
        List<FloorColor> palette) {

    public ColorFloorConfig {
        if (minimumPlayers < 1 || maximumPlayers < minimumPlayers
                || maximumPlayers > 64 || rounds < 1 || rounds > 10_000) {
            throw new IllegalArgumentException("bounds are invalid");
        }
        if (reactionWindow == null || reactionWindow.isNegative() || reactionWindow.isZero()) {
            throw new IllegalArgumentException("reactionWindow must be positive");
        }
        rulesetRevision = Objects.requireNonNull(rulesetRevision, "rulesetRevision").trim();
        if (!rulesetRevision.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}")) {
            throw new IllegalArgumentException("invalid revision");
        }
        isolation = Objects.requireNonNull(isolation, "isolation");
        palette = List.copyOf(Objects.requireNonNull(palette, "palette"));
        if (palette.size() < 2 || palette.size() > FloorColor.values().length
                || palette.stream().anyMatch(Objects::isNull)
                || palette.stream().distinct().count() != palette.size()) {
            throw new IllegalArgumentException("palette must contain unique supported colours");
        }
    }

    public ColorFloorConfig(
            int minimumPlayers,
            int maximumPlayers,
            int rounds,
            Duration reactionWindow,
            long seed,
            String rulesetRevision) {
        this(minimumPlayers, maximumPlayers, rounds, reactionWindow, seed, rulesetRevision,
                IsolationPolicy.strictNoProgress(), List.of(FloorColor.values()));
    }

    public ColorFloorConfig(
            int minimumPlayers,
            int maximumPlayers,
            int rounds,
            Duration reactionWindow,
            long seed,
            String rulesetRevision,
            IsolationPolicy isolation) {
        this(minimumPlayers, maximumPlayers, rounds, reactionWindow, seed, rulesetRevision,
                isolation, List.of(FloorColor.values()));
    }
}
