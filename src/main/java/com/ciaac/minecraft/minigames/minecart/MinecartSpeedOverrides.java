package com.ciaac.minecraft.minigames.minecart;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Substituições temporárias e imutáveis, aplicadas sobre a política aceite do ficheiro. */
public record MinecartSpeedOverrides(
        Optional<Double> defaultBlocksPerSecond,
        Map<String, MinecartSpeedConfiguration.WorldOverride> worlds) {

    public MinecartSpeedOverrides {
        defaultBlocksPerSecond = Objects.requireNonNull(defaultBlocksPerSecond, "defaultBlocksPerSecond");
        defaultBlocksPerSecond.ifPresent(speed -> MinecartSpeedConfiguration.requireSpeed(
                speed, "A velocidade temporária predefinida"));
        Objects.requireNonNull(worlds, "worlds");
        LinkedHashMap<String, MinecartSpeedConfiguration.WorldOverride> copy = new LinkedHashMap<>();
        worlds.forEach((name, override) -> copy.put(
                validWorldName(name), Objects.requireNonNull(override, "world override")));
        worlds = Map.copyOf(copy);
    }

    public static MinecartSpeedOverrides empty() {
        return new MinecartSpeedOverrides(Optional.empty(), Map.of());
    }

    public boolean active() {
        return defaultBlocksPerSecond.isPresent() || !worlds.isEmpty();
    }

    public MinecartSpeedOverrides withDefault(double speed) {
        return new MinecartSpeedOverrides(Optional.of(speed), worlds);
    }

    public MinecartSpeedOverrides withWorld(String name, UUID worldId, double speed) {
        LinkedHashMap<String, MinecartSpeedConfiguration.WorldOverride> copy = new LinkedHashMap<>(worlds);
        copy.put(validWorldName(name), new MinecartSpeedConfiguration.WorldOverride(worldId, speed));
        return new MinecartSpeedOverrides(defaultBlocksPerSecond, copy);
    }

    public MinecartSpeedOverrides withoutWorld(String name) {
        String validName = validWorldName(name);
        if (!worlds.containsKey(validName)) return this;
        LinkedHashMap<String, MinecartSpeedConfiguration.WorldOverride> copy = new LinkedHashMap<>(worlds);
        copy.remove(validName);
        return new MinecartSpeedOverrides(defaultBlocksPerSecond, copy);
    }

    public Resolution resolve(MinecartSpeedConfiguration base, String worldName, UUID worldId) {
        Objects.requireNonNull(base, "base");
        String validName = validWorldName(worldName);
        Objects.requireNonNull(worldId, "worldId");
        MinecartSpeedConfiguration.WorldOverride world = worlds.get(validName);
        if (world != null) {
            return world.worldId().equals(worldId)
                    ? Resolution.speed(world.blocksPerSecond())
                    : Resolution.mismatchedIdentity();
        }
        if (defaultBlocksPerSecond.isPresent()) {
            return Resolution.speed(defaultBlocksPerSecond.orElseThrow());
        }
        if (!base.enabled()) return Resolution.inactive();
        Optional<Double> configured = base.speedFor(validName, worldId);
        return configured.isPresent()
                ? Resolution.speed(configured.orElseThrow())
                : Resolution.mismatchedIdentity();
    }

    private static String validWorldName(String name) {
        String candidate = Objects.requireNonNull(name, "world name").strip();
        if (candidate.isEmpty() || candidate.length() > 64 || candidate.indexOf('\n') >= 0
                || candidate.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("O nome do mundo é inválido.");
        }
        return candidate;
    }

    public record Resolution(Optional<Double> blocksPerSecond, boolean identityMismatch) {
        public Resolution {
            blocksPerSecond = Objects.requireNonNull(blocksPerSecond, "blocksPerSecond");
            if (blocksPerSecond.isPresent() && identityMismatch) {
                throw new IllegalArgumentException("A resolução da velocidade é incoerente.");
            }
        }

        static Resolution speed(double speed) { return new Resolution(Optional.of(speed), false); }
        static Resolution inactive() { return new Resolution(Optional.empty(), false); }
        static Resolution mismatchedIdentity() { return new Resolution(Optional.empty(), true); }
    }
}
