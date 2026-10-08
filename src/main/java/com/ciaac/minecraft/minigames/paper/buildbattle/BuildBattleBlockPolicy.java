package com.ciaac.minecraft.minigames.paper.buildbattle;

import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/** Stateless construction blocks: no inventories, fluids, gravity, circuitry or entity spawning. */
public final class BuildBattleBlockPolicy {
    private static final Set<String> BLOCKS = Set.of(
            "AIR", "STONE", "COBBLESTONE", "MOSSY_COBBLESTONE", "SMOOTH_STONE", "STONE_BRICKS",
            "MOSSY_STONE_BRICKS", "CRACKED_STONE_BRICKS", "CHISELED_STONE_BRICKS", "BRICKS",
            "GRANITE", "POLISHED_GRANITE", "DIORITE", "POLISHED_DIORITE", "ANDESITE", "POLISHED_ANDESITE",
            "DEEPSLATE", "COBBLED_DEEPSLATE", "POLISHED_DEEPSLATE", "DEEPSLATE_BRICKS", "DEEPSLATE_TILES",
            "SANDSTONE", "SMOOTH_SANDSTONE", "CUT_SANDSTONE", "CHISELED_SANDSTONE",
            "RED_SANDSTONE", "SMOOTH_RED_SANDSTONE", "CUT_RED_SANDSTONE", "CHISELED_RED_SANDSTONE",
            "GLASS", "TINTED_GLASS", "TERRACOTTA", "QUARTZ_BLOCK", "SMOOTH_QUARTZ", "QUARTZ_BRICKS",
            "CHISELED_QUARTZ_BLOCK", "QUARTZ_PILLAR", "NETHER_BRICKS", "RED_NETHER_BRICKS",
            "END_STONE", "END_STONE_BRICKS", "PURPUR_BLOCK", "PURPUR_PILLAR", "PRISMARINE",
            "PRISMARINE_BRICKS", "DARK_PRISMARINE", "OBSIDIAN", "CRYING_OBSIDIAN", "BEDROCK",
            "GLOWSTONE", "SEA_LANTERN", "BLACKSTONE", "POLISHED_BLACKSTONE", "POLISHED_BLACKSTONE_BRICKS",
            "GOLD_BLOCK", "IRON_BLOCK", "DIAMOND_BLOCK", "EMERALD_BLOCK", "LAPIS_BLOCK", "COAL_BLOCK",
            "NETHERITE_BLOCK", "AMETHYST_BLOCK", "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "CLAY", "CALCITE", "TUFF");
    private static final Set<String> COLORS = Set.of("WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW",
            "LIME", "PINK", "GRAY", "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK");
    private static final Set<String> WOODS = Set.of("OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK",
            "MANGROVE", "CHERRY", "PALE_OAK", "BAMBOO", "CRIMSON", "WARPED");

    private BuildBattleBlockPolicy() { }

    public static boolean allows(Material material) {
        if (material == null) return false;
        String name = material.name();
        if (BLOCKS.contains(name)) return true;
        for (String color : COLORS) {
            if (name.equals(color + "_WOOL") || name.equals(color + "_CONCRETE")
                    || name.equals(color + "_STAINED_GLASS") || name.equals(color + "_TERRACOTTA")) return true;
        }
        for (String wood : WOODS) {
            if (name.equals(wood + "_PLANKS") || name.equals(wood + "_LOG") || name.equals(wood + "_WOOD")
                    || name.equals("STRIPPED_" + wood + "_LOG") || name.equals("STRIPPED_" + wood + "_WOOD")) return true;
        }
        return false;
    }

    public static boolean allows(BlockData data) { return data != null && allows(data.getMaterial()); }
}
