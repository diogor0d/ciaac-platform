package com.ciaac.minecraft.minigames.core;

import java.util.Locale;
import java.util.Optional;

public enum GameKey {
    ARENA("arena", "Coliseu", GameLocationPolicy.SPAWN_SAFEZONE),
    BUILD_BATTLE("build-battle", "Build Battle", GameLocationPolicy.DEDICATED_WORLD),
    HOT_POTATO("hot-potato", "Batata Quente", GameLocationPolicy.DEDICATED_WORLD),
    KNOCKBACK_SUMO("knockback-sumo", "Knockback Sumo", GameLocationPolicy.SPAWN_SAFEZONE),
    CHECKPOINT_PARKOUR("checkpoint-parkour", "Parkour Cronometrado", GameLocationPolicy.SPAWN_SAFEZONE),
    ARCHERY_RANGE("archery-range", "Campo de Tiro com Arco", GameLocationPolicy.SPAWN_SAFEZONE),
    ANVIL_DODGE("anvil-dodge", "Fuga às Bigornas", GameLocationPolicy.SPAWN_SAFEZONE),
    COLOR_FLOOR("color-floor", "Chão de Cores", GameLocationPolicy.SPAWN_SAFEZONE),
    ELYTRA_RINGS("elytra-rings", "Anéis de Elytra", GameLocationPolicy.DEDICATED_WORLD);

    private final String id;
    private final String portugueseName;
    private final GameLocationPolicy locationPolicy;

    GameKey(String id, String portugueseName, GameLocationPolicy locationPolicy) {
        this.id = id;
        this.portugueseName = portugueseName;
        this.locationPolicy = locationPolicy;
    }

    public String id() {
        return id;
    }

    public String portugueseName() {
        return portugueseName;
    }

    public GameLocationPolicy locationPolicy() {
        return locationPolicy;
    }

    public static Optional<GameKey> fromId(String candidate) {
        if (candidate == null) {
            return Optional.empty();
        }
        String normalized = candidate.toLowerCase(Locale.ROOT);
        for (GameKey key : values()) {
            if (key.id.equals(normalized)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }
}
