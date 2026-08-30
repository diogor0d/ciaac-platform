package com.ciaac.minecraft.minigames.hotpotato;

import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import java.time.Duration;
import java.util.Objects;

/** Immutable, validated adapter configuration for one dedicated-world instance. */
public record HotPotatoConfig(int minimumPlayers, int maximumPlayers, Duration fuse,
                              Duration suddenDeathFuse, Duration passCooldown,
                              double passRange, Duration matchTimeout,
                              String rulesetRevision, IsolationPolicy isolation,
                              Duration fuseReductionPerElimination) {
    public HotPotatoConfig {
        if (minimumPlayers < 2 || maximumPlayers < minimumPlayers || maximumPlayers > 64)
            throw new IllegalArgumentException("player bounds are invalid");
        positive(fuse, "fuse"); positive(suddenDeathFuse, "suddenDeathFuse");
        if (suddenDeathFuse.compareTo(fuse) > 0) {
            throw new IllegalArgumentException("suddenDeathFuse cannot exceed the initial fuse");
        }
        if (fuseReductionPerElimination == null || fuseReductionPerElimination.isNegative()
                || fuseReductionPerElimination.compareTo(fuse) > 0) {
            throw new IllegalArgumentException("fuseReductionPerElimination is invalid");
        }
        if (passCooldown == null || passCooldown.isNegative()) throw new IllegalArgumentException("passCooldown is invalid");
        if (!Double.isFinite(passRange) || passRange <= 0 || passRange > 32) throw new IllegalArgumentException("passRange is invalid");
        positive(matchTimeout, "matchTimeout");
        rulesetRevision = Objects.requireNonNull(rulesetRevision).trim();
        if (!rulesetRevision.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}")) throw new IllegalArgumentException("rulesetRevision is invalid");
        isolation = Objects.requireNonNull(isolation, "isolation");
    }
    public HotPotatoConfig(int min, int max, Duration fuse, Duration sudden, Duration cooldown, double range, Duration timeout, String rev) {
        this(min, max, fuse, sudden, cooldown, range, timeout, rev,
                IsolationPolicy.strictNoProgress(), Duration.ZERO);
    }
    public HotPotatoConfig(int min, int max, Duration fuse, Duration sudden, Duration cooldown,
                           double range, Duration timeout, String rev, IsolationPolicy isolation) {
        this(min, max, fuse, sudden, cooldown, range, timeout, rev, isolation, Duration.ZERO);
    }
    public HotPotatoConfig(int min, int max, Duration fuse, Duration sudden, Duration cooldown,
                           double range, Duration timeout, String rev, Duration reduction) {
        this(min, max, fuse, sudden, cooldown, range, timeout, rev,
                IsolationPolicy.strictNoProgress(), reduction);
    }
    private static void positive(Duration value, String name) { if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException(name + " must be positive"); }
}
