package com.ciaac.minecraft.minigames.paper.arena;

import com.ciaac.minecraft.minigames.arena.ArenaFormatPolicy;
import com.ciaac.minecraft.minigames.arena.ArenaLocationPolicy;
import com.ciaac.minecraft.minigames.arena.ArenaFormat;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;

/** Validated, runtime-supplied settings for the disabled-by-default arena adapter. */
public record ColiseumSettings(
        boolean enabled,
        Optional<UUID> worldId,
        Optional<ArenaLocationPolicy> locations,
        Optional<Location> teamASpawn,
        Optional<Location> teamBSpawn,
        Optional<Location> spectatorFallback,
        ArenaFormatPolicy formatPolicy,
        Duration readyTimeout,
        Duration admissionTokenLifetime,
        Duration roundDuration,
        Duration reconnectGrace,
        boolean friendlyFire,
        Set<String> prohibitedMaterials,
        boolean stakedEnabled,
        Set<ArenaFormat> stakedFormats,
        Set<String> stakedProhibitedMaterials,
        Duration stakeConsentTimeout,
        String stakedNoContestPolicy,
        String rulesetDigest) {

    public ColiseumSettings {
        worldId = Objects.requireNonNull(worldId, "worldId");
        locations = Objects.requireNonNull(locations, "locations");
        teamASpawn = copyLocation(teamASpawn, "teamASpawn");
        teamBSpawn = copyLocation(teamBSpawn, "teamBSpawn");
        spectatorFallback = copyLocation(spectatorFallback, "spectatorFallback");
        formatPolicy = Objects.requireNonNull(formatPolicy, "formatPolicy");
        readyTimeout = positive(readyTimeout, "readyTimeout");
        admissionTokenLifetime = positive(admissionTokenLifetime, "admissionTokenLifetime");
        roundDuration = positive(roundDuration, "roundDuration");
        reconnectGrace = nonNegative(reconnectGrace, "reconnectGrace");
        prohibitedMaterials = normalizeMaterials(prohibitedMaterials);
        stakedFormats = Set.copyOf(Objects.requireNonNull(stakedFormats, "stakedFormats"));
        stakedProhibitedMaterials = normalizeMaterials(stakedProhibitedMaterials);
        stakeConsentTimeout = positive(stakeConsentTimeout, "stakeConsentTimeout");
        stakedNoContestPolicy = boundedCode(stakedNoContestPolicy, "stakedNoContestPolicy");
        rulesetDigest = boundedToken(rulesetDigest, "rulesetDigest");
        if (stakedEnabled && (!stakedFormats.equals(Set.of(ArenaFormat.standard(1))))) {
            throw new IllegalArgumentException("Staked play is restricted to an explicit 1v1 format");
        }
        if (stakedEnabled && !stakedNoContestPolicy.equals("REFUND")) {
            throw new IllegalArgumentException("Staked no-contest policy must be REFUND");
        }
        if (enabled) {
            UUID world = worldId.orElseThrow(() -> new IllegalArgumentException("Enabled arena needs a world"));
            ArenaLocationPolicy named = locations.orElseThrow(
                    () -> new IllegalArgumentException("Enabled arena needs named locations"));
            Location a = teamASpawn.orElseThrow(() -> new IllegalArgumentException("Enabled arena needs team A spawn"));
            Location b = teamBSpawn.orElseThrow(() -> new IllegalArgumentException("Enabled arena needs team B spawn"));
            spectatorFallback.orElseThrow(() -> new IllegalArgumentException(
                    "Enabled arena needs a spectator fallback"));
            requireWorld(a, world, "teamASpawn");
            requireWorld(b, world, "teamBSpawn");
            requireWorld(spectatorFallback.orElseThrow(), world, "spectatorFallback");
            if (named.world().equals("__SET_ME__")) {
                throw new IllegalArgumentException("Enabled arena location names must be configured");
            }
        }
    }

    /** Compatibility constructor for existing source callers; new assembly uses explicit timers. */
    public ColiseumSettings(
            boolean enabled,
            Optional<UUID> worldId,
            Optional<ArenaLocationPolicy> locations,
            Optional<Location> teamASpawn,
            Optional<Location> teamBSpawn,
            Optional<Location> spectatorFallback,
            ArenaFormatPolicy formatPolicy,
            Duration readyTimeout,
            Duration admissionTokenLifetime,
            boolean friendlyFire,
            Set<String> prohibitedMaterials) {
        this(enabled, worldId, locations, teamASpawn, teamBSpawn, spectatorFallback,
                formatPolicy, readyTimeout, admissionTokenLifetime, Duration.ofMinutes(5),
                Duration.ZERO, friendlyFire, prohibitedMaterials, false, Set.of(), Set.of(),
                Duration.ofSeconds(60), "REFUND", "arena-v1");
    }

    public static ColiseumSettings disabled() {
        return new ColiseumSettings(false, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), ArenaFormatPolicy.defaultPolicy(),
                Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ZERO, false, Set.of(), false, Set.of(), Set.of(),
                Duration.ofSeconds(60), "REFUND", "arena-v1");
    }

    private static Optional<Location> copyLocation(Optional<Location> value, String name) {
        Objects.requireNonNull(value, name);
        return value.map(location -> Objects.requireNonNull(location, name).clone());
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Duration nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    private static Set<String> normalizeMaterials(Set<String> values) {
        Objects.requireNonNull(values, "prohibitedMaterials");
        return Set.copyOf(values.stream().map(value -> {
            String normalized = Objects.requireNonNull(value, "prohibited material").trim().toUpperCase(java.util.Locale.ROOT);
            if (!normalized.matches("[A-Z0-9_:.-]{1,128}")) throw new IllegalArgumentException("Invalid prohibited material");
            return normalized;
        }).toList());
    }

    private static String boundedCode(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException(name + " is invalid");
        return normalized;
    }

    private static String boundedToken(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) throw new IllegalArgumentException(name + " is invalid");
        return normalized;
    }

    private static void requireWorld(Location location, UUID expected, String name) {
        if (location.getWorld() == null || !expected.equals(location.getWorld().getUID())) {
            throw new IllegalArgumentException(name + " is not in the configured arena world");
        }
        if (!Double.isFinite(location.getX()) || !Double.isFinite(location.getY()) || !Double.isFinite(location.getZ())
                || !Float.isFinite(location.getYaw()) || !Float.isFinite(location.getPitch())) {
            throw new IllegalArgumentException(name + " contains a non-finite coordinate");
        }
    }
}
