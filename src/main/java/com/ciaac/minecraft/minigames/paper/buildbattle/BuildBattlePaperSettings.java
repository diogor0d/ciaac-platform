package com.ciaac.minecraft.minigames.paper.buildbattle;

import com.ciaac.minecraft.minigames.buildbattle.BuildBattleConfig;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattlePlot;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleThemePool;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;

/** Runtime-supplied dedicated-world geometry; no live coordinates belong in Git. */
public record BuildBattlePaperSettings(
        boolean enabled,
        BuildBattleConfig config,
        BuildBattleThemePool themes,
        World world,
        String participantRegionId,
        Location waitingSpawn,
        Map<BuildBattlePlot, PlotSettings> plots,
        Duration countdown,
        Duration buildDuration,
        Duration voteDuration,
        Duration tokenLifetime,
        Duration themeVoteDuration,
        Duration reviewDuration) {
    /** Compatibility constructor for the assembler/configuration contract. */
    public BuildBattlePaperSettings(
            boolean enabled,
            BuildBattleConfig config,
            BuildBattleThemePool themes,
            World world,
            String participantRegionId,
            Location waitingSpawn,
            Map<BuildBattlePlot, PlotSettings> plots,
            Duration countdown,
            Duration buildDuration,
            Duration voteDuration,
            Duration tokenLifetime) {
        this(enabled, config, themes, world, participantRegionId, waitingSpawn, plots,
                countdown, buildDuration, voteDuration, tokenLifetime,
                Duration.ofSeconds(20), Duration.ofSeconds(15));
    }

    public BuildBattlePaperSettings {
        config = Objects.requireNonNull(config, "config"); themes = Objects.requireNonNull(themes, "themes");
        if (participantRegionId == null || !participantRegionId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("participantRegionId is invalid");
        waitingSpawn = enabled ? copyLocation(waitingSpawn, "waitingSpawn") : (waitingSpawn == null ? null : waitingSpawn.clone());
        plots = Map.copyOf(Objects.requireNonNull(plots, "plots"));
        if (plots.size() < config.maximumPlayers()) throw new IllegalArgumentException("not enough plots");
        plots.forEach((plot, value) -> { Objects.requireNonNull(plot, "plot"); Objects.requireNonNull(value, "plot settings"); });
        countdown = positive(countdown, "countdown"); buildDuration = positive(buildDuration, "buildDuration"); voteDuration = positive(voteDuration, "voteDuration"); tokenLifetime = positive(tokenLifetime, "tokenLifetime"); themeVoteDuration = positive(themeVoteDuration, "themeVoteDuration"); reviewDuration = positive(reviewDuration, "reviewDuration");
        if (enabled) {
            World expectedWorld = Objects.requireNonNull(world, "enabled Build Battle needs world");
            requireWorld(waitingSpawn, expectedWorld, "waitingSpawn");
            plots.values().forEach(value -> requireWorld(value.spawn(), expectedWorld, "plot spawn"));
        }
    }
    public static BuildBattlePaperSettings disabled(BuildBattleConfig config, BuildBattleThemePool themes, String regionId, Map<BuildBattlePlot, PlotSettings> plots) {
        return new BuildBattlePaperSettings(false, config, themes, null, regionId, null, plots,
                Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofMinutes(10));
    }
    public record PlotSettings(String regionId, Location spawn) {
        public PlotSettings { if (regionId == null || !regionId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("plot regionId is invalid"); spawn = copyLocation(spawn, "plot spawn"); }
    }
    private static Location copyLocation(Location value, String name) { return Objects.requireNonNull(value, name).clone(); }
    private static Duration positive(Duration value, String name) { Objects.requireNonNull(value, name); if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive"); return value; }
    private static void requireWorld(Location location, World world, String name) {
        World actual = Objects.requireNonNull(location, name).getWorld();
        if (actual == null || !actual.getUID().equals(world.getUID()) || !actual.getName().equals(world.getName())) {
            throw new IllegalArgumentException(name + " is outside configured world");
        }
    }
}
