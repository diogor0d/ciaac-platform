package com.ciaac.minecraft.minigames.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ModuleCatalog {
    private final Map<GameKey, ModuleDefinition> modules;

    public ModuleCatalog(List<ModuleDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        EnumMap<GameKey, ModuleDefinition> indexed = new EnumMap<>(GameKey.class);
        for (ModuleDefinition definition : definitions) {
            if (indexed.put(definition.key(), definition) != null) {
                throw new IllegalArgumentException("Duplicate module: " + definition.key());
            }
        }
        if (indexed.size() != GameKey.values().length) {
            throw new IllegalArgumentException("The catalog must classify every known minigame");
        }
        this.modules = Map.copyOf(indexed);
    }

    public ModuleDefinition get(GameKey key) {
        return Objects.requireNonNull(modules.get(key), "Unknown module " + key);
    }

    public List<ModuleDefinition> all() {
        return modules.values().stream()
                .sorted((left, right) -> left.key().ordinal() - right.key().ordinal())
                .toList();
    }

    public static ModuleCatalog foundationCatalog() {
        return new ModuleCatalog(List.of(
                sourceImplemented(GameKey.ARENA),
                sourceImplemented(GameKey.BUILD_BATTLE),
                sourceImplemented(GameKey.HOT_POTATO),
                sourceImplemented(GameKey.KNOCKBACK_SUMO),
                sourceImplemented(GameKey.CHECKPOINT_PARKOUR),
                sourceImplemented(GameKey.ARCHERY_RANGE),
                sourceImplemented(GameKey.ANVIL_DODGE),
                sourceImplemented(GameKey.COLOR_FLOOR),
                sourceImplemented(GameKey.ELYTRA_RINGS)));
    }

    private static ModuleDefinition sourceImplemented(GameKey key) {
        return new ModuleDefinition(
                key,
                ImplementationStage.SOURCE_IMPLEMENTED,
                false,
                "Implementação em código; entrada fechada até à validação integrada.");
    }
}
