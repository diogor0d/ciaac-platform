package com.ciaac.minecraft.minigames.paper.colorfloor;

import com.ciaac.minecraft.minigames.colorfloor.ColorFloorConfig;
import com.ciaac.minecraft.minigames.colorfloor.FloorColor;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** Immutable floor template and Bukkit boundary for Color Floor. */
public record ColorFloorPaperSettings(boolean enabled, ColorFloorConfig config, World world,
                                      String participantRegionId, Location start,
                                      Map<ColorFloorCell, FloorColor> colors,
                                      Map<ColorFloorCell, BlockData> template,
                                      Duration announceDuration) {
    public ColorFloorPaperSettings(boolean enabled, ColorFloorConfig config, World world,
                                   String participantRegionId, Location start,
                                   Map<ColorFloorCell, FloorColor> colors,
                                   Map<ColorFloorCell, BlockData> template) {
        this(enabled, config, world, participantRegionId, start, colors, template,
                Objects.requireNonNull(config, "config").reactionWindow());
    }

    public ColorFloorPaperSettings {
        config = Objects.requireNonNull(config, "config");
        announceDuration = Objects.requireNonNull(announceDuration, "announceDuration");
        if (announceDuration.isNegative() || announceDuration.isZero()) {
            throw new IllegalArgumentException("announceDuration must be positive");
        }
        if (participantRegionId == null || !participantRegionId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("participantRegionId is invalid");
        colors = Map.copyOf(Objects.requireNonNull(colors, "colors"));
        var copied = new LinkedHashMap<ColorFloorCell, BlockData>();
        for (var entry : Objects.requireNonNull(template, "template").entrySet()) copied.put(Objects.requireNonNull(entry.getKey()), Objects.requireNonNull(entry.getValue()).clone());
        template = Map.copyOf(copied);
        if (colors.size() > 1_000_000 || !template.keySet().equals(colors.keySet())
                || (enabled && colors.isEmpty())) {
            throw new IllegalArgumentException("template and color cells must match exactly within the safe bound");
        }
        if (enabled) { world = Objects.requireNonNull(world, "world"); start = copyInWorld(start, world); }
        else start = start == null ? null : start.clone();
    }
    public static ColorFloorPaperSettings disabled(ColorFloorConfig config, String regionId) {
        return new ColorFloorPaperSettings(false, config, null, regionId, null,
                Map.of(), Map.of(), config.reactionWindow());
    }
    private static Location copyInWorld(Location value, World world) {
        Objects.requireNonNull(value, "start");
        World actual = value.getWorld();
        if (actual == null || !actual.getUID().equals(world.getUID()) || !actual.getName().equals(world.getName())) {
            throw new IllegalArgumentException("start belongs to another world");
        }
        return value.clone();
    }
}
