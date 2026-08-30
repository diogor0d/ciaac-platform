package com.ciaac.minecraft.minigames.paper.anvildodge;

import com.ciaac.minecraft.minigames.anvildodge.AnvilDodgeConfig;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;

/** Bukkit-only coordinates and admission identifiers; no live values belong in Git. */
public record AnvilDodgePaperSettings(
        boolean enabled,
        AnvilDodgeConfig config,
        World world,
        String participantRegionId,
        Location start,
        Location floorOrigin,
        int floorWidth,
        int floorDepth) {

    public AnvilDodgePaperSettings {
        config = Objects.requireNonNull(config, "config");
        if (participantRegionId == null || !participantRegionId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("participantRegionId is invalid");
        }
        if (floorWidth < 1 || floorDepth < 1 || floorWidth * (long) floorDepth > 4096) {
            throw new IllegalArgumentException("floor dimensions are invalid");
        }
        if (enabled) {
            world = Objects.requireNonNull(world, "world");
            start = copyInWorld(start, world, "start");
            floorOrigin = copyInWorld(floorOrigin, world, "floorOrigin");
        } else {
            // Disabled settings remain constructible without a loaded world.
            start = start == null ? null : start.clone();
            floorOrigin = floorOrigin == null ? null : floorOrigin.clone();
        }
    }

    public static AnvilDodgePaperSettings disabled(AnvilDodgeConfig config, String participantRegionId) {
        return new AnvilDodgePaperSettings(false, config, null, participantRegionId, null, null, 1, 1);
    }

    private static Location copyInWorld(Location value, World expected, String name) {
        Objects.requireNonNull(value, name);
        if (value.getWorld() != expected) throw new IllegalArgumentException(name + " belongs to another world");
        return value.clone();
    }
}
