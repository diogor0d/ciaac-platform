package com.ciaac.minecraft.minigames.buildbattle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.random.RandomGenerator;

/** Bounded, duplicate-free theme pool. Selection is deterministic for a supplied seed/RNG. */
public final class BuildBattleThemePool {
    private final List<BuildBattleTheme> themes;

    public BuildBattleThemePool(Collection<BuildBattleTheme> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty() || values.size() > 256) {
            throw new IllegalArgumentException("theme pool must contain 1..256 themes");
        }
        LinkedHashMap<String, BuildBattleTheme> map = new LinkedHashMap<>();
        for (BuildBattleTheme theme : values) {
            Objects.requireNonNull(theme, "theme");
            if (map.put(theme.id(), theme) != null) {
                throw new IllegalArgumentException("duplicate theme id");
            }
        }
        themes = List.copyOf(map.values());
    }

    public List<BuildBattleTheme> themes() {
        return themes;
    }

    public BuildBattleTheme select(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        return themes.get(random.nextInt(themes.size()));
    }

    /**
     * Returns a stable, duplicate-free option set for one match. A local RNG
     * keeps option order independent from command/event arrival order.
     */
    public List<BuildBattleTheme> options(long seed, int count) {
        if (count < 1 || count > themes.size() || count > 3) {
            throw new IllegalArgumentException("theme option count must be 1..3 and fit the pool");
        }
        List<BuildBattleTheme> shuffled = new ArrayList<>(themes);
        Collections.shuffle(shuffled, new Random(seed));
        return List.copyOf(shuffled.subList(0, count));
    }
}
