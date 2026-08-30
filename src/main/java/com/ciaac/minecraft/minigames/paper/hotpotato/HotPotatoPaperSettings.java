package com.ciaac.minecraft.minigames.paper.hotpotato;

import com.ciaac.minecraft.minigames.hotpotato.HotPotatoConfig;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;

/** Explicit dedicated-world settings for one Hot Potato instance. */
public record HotPotatoPaperSettings(
        boolean enabled,
        HotPotatoConfig config,
        World world,
        String participantRegionId,
        List<Location> spawns,
        Duration countdown,
        Duration tokenLifetime) {
    public HotPotatoPaperSettings {
        config = Objects.requireNonNull(config, "config");
        if (participantRegionId == null || !participantRegionId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("participantRegionId is invalid");
        spawns = copyLocations(spawns);
        countdown = positive(countdown, "countdown"); tokenLifetime = positive(tokenLifetime, "tokenLifetime");
        if (enabled) {
            World expectedWorld = Objects.requireNonNull(world, "enabled Hot Potato needs world");
            if (spawns.size() < config.maximumPlayers()) throw new IllegalArgumentException("not enough Hot Potato spawns");
            spawns.forEach(location -> requireWorld(location, expectedWorld));
        }
    }
    public static HotPotatoPaperSettings disabled(HotPotatoConfig config, String regionId) {
        return new HotPotatoPaperSettings(false, config, null, regionId, List.of(), Duration.ofSeconds(20), Duration.ofMinutes(10));
    }
    private static List<Location> copyLocations(List<Location> values) { Objects.requireNonNull(values, "spawns"); return values.stream().map(value -> Objects.requireNonNull(value, "spawn").clone()).toList(); }
    private static Duration positive(Duration value, String name) { Objects.requireNonNull(value, name); if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive"); return value; }
    private static void requireWorld(Location location, World world) { if (location.getWorld() != world) throw new IllegalArgumentException("spawn belongs to another world"); }
}
