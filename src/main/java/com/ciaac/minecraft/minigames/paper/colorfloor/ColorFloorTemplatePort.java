package com.ciaac.minecraft.minigames.paper.colorfloor;

import com.ciaac.minecraft.minigames.colorfloor.FloorColor;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** Supplies an operator-reviewed immutable floor template; live auto-capture is forbidden. */
public interface ColorFloorTemplatePort {
    boolean available();

    Optional<Template> load(World world, CuboidRegion floor, String rulesetRevision);

    record Template(
            String revision,
            String checksumSha256,
            Map<ColorFloorCell, FloorColor> colors,
            Map<ColorFloorCell, BlockData> blocks) {
        public Template {
            if (revision == null || !revision.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) {
                throw new IllegalArgumentException("template revision is invalid");
            }
            if (checksumSha256 == null || !checksumSha256.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("template checksum is invalid");
            }
            colors = Map.copyOf(Objects.requireNonNull(colors, "colors"));
            java.util.LinkedHashMap<ColorFloorCell, BlockData> copied = new java.util.LinkedHashMap<>();
            for (Map.Entry<ColorFloorCell, BlockData> entry : Objects.requireNonNull(blocks, "blocks").entrySet()) {
                copied.put(Objects.requireNonNull(entry.getKey(), "block cell"),
                        Objects.requireNonNull(entry.getValue(), "block data").clone());
            }
            blocks = Map.copyOf(copied);
            if (colors.isEmpty() || !blocks.keySet().equals(colors.keySet())) {
                throw new IllegalArgumentException("template color and block cells must match exactly");
            }
        }

        @Override public Map<ColorFloorCell, BlockData> blocks() {
            java.util.LinkedHashMap<ColorFloorCell, BlockData> copy = new java.util.LinkedHashMap<>();
            blocks.forEach((cell, data) -> copy.put(cell, data.clone()));
            return Map.copyOf(copy);
        }
    }
}
