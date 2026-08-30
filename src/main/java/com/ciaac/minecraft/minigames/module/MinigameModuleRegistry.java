package com.ciaac.minecraft.minigames.module;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete registry: missing modules are never silently treated as open. */
public final class MinigameModuleRegistry {
    private final Map<GameKey, MinigameModule> modules;

    public MinigameModuleRegistry(List<MinigameModule> values) {
        Objects.requireNonNull(values, "values");
        EnumMap<GameKey, MinigameModule> indexed = new EnumMap<>(GameKey.class);
        for (MinigameModule module : values) {
            Objects.requireNonNull(module, "module");
            if (indexed.put(module.key(), module) != null) {
                throw new IllegalArgumentException("Duplicate module " + module.key());
            }
        }
        if (indexed.size() != GameKey.values().length) {
            throw new IllegalArgumentException("Every known minigame must have one module entry");
        }
        modules = Map.copyOf(indexed);
    }

    public MinigameModule get(GameKey key) {
        return Objects.requireNonNull(modules.get(Objects.requireNonNull(key, "key")), "Unknown module");
    }

    public List<MinigameModule> all() {
        List<MinigameModule> ordered = new ArrayList<>(modules.values());
        ordered.sort(java.util.Comparator.comparingInt(value -> value.key().ordinal()));
        return List.copyOf(ordered);
    }

    public static MinigameModuleRegistry allUnavailable(String reasonPtPt) {
        return new MinigameModuleRegistry(java.util.Arrays.stream(GameKey.values())
                .map(key -> (MinigameModule) new UnavailableMinigameModule(key, reasonPtPt))
                .toList());
    }
}
