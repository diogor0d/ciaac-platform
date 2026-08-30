package com.ciaac.minecraft.minigames.minecart;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable per-world terminal-speed policy expressed in blocks per second. */
public record MinecartSpeedConfiguration(
        int schemaVersion,
        boolean enabled,
        double defaultBlocksPerSecond,
        Map<String, WorldOverride> worlds) {
    public static final double DEFAULT_BLOCKS_PER_SECOND = 16.0;
    public static final double MINIMUM_BLOCKS_PER_SECOND = 0.1;
    public static final double MAXIMUM_BLOCKS_PER_SECOND = 64.0;

    public MinecartSpeedConfiguration {
        if (schemaVersion != 1) throw new IllegalArgumentException("Versão de configuração de carrinhos não suportada.");
        requireSpeed(defaultBlocksPerSecond, "A velocidade predefinida");
        Objects.requireNonNull(worlds, "worlds");
        java.util.LinkedHashMap<String, WorldOverride> copy = new java.util.LinkedHashMap<>();
        worlds.forEach((name, override) -> {
            String normalized = Objects.requireNonNull(name, "world name").strip();
            if (normalized.isEmpty() || normalized.length() > 64 || normalized.indexOf('\n') >= 0
                    || normalized.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("O nome de um mundo é inválido.");
            }
            if (copy.putIfAbsent(normalized, Objects.requireNonNull(override, "world override")) != null) {
                throw new IllegalArgumentException("O mundo " + normalized + " está repetido.");
            }
        });
        worlds = Map.copyOf(copy);
    }

    public static MinecartSpeedConfiguration disabled() {
        return new MinecartSpeedConfiguration(1, false, DEFAULT_BLOCKS_PER_SECOND, Map.of());
    }

    public Optional<Double> speedFor(String worldName, UUID worldId) {
        WorldOverride override = worlds.get(Objects.requireNonNull(worldName, "worldName"));
        if (override == null) return Optional.of(defaultBlocksPerSecond);
        if (!override.worldId().equals(Objects.requireNonNull(worldId, "worldId"))) return Optional.empty();
        return Optional.of(override.blocksPerSecond());
    }

    static void requireSpeed(double speed, String label) {
        if (!Double.isFinite(speed) || speed < MINIMUM_BLOCKS_PER_SECOND
                || speed > MAXIMUM_BLOCKS_PER_SECOND) {
            throw new IllegalArgumentException(label + " deve estar entre 0,1 e 64,0 blocos/s.");
        }
    }

    public record WorldOverride(UUID worldId, double blocksPerSecond) {
        public WorldOverride {
            Objects.requireNonNull(worldId, "worldId");
            requireSpeed(blocksPerSecond, "A velocidade do mundo");
        }
    }
}
