package com.ciaac.minecraft.minigames.paper.buildbattle;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class BuildBattleBlockPolicyTest {
    @Test void safeConstructionPaletteExcludesPersistentStateAndEnvironmentalEffects() {
        for (Material material : new Material[] {Material.STONE, Material.BRICKS, Material.OAK_PLANKS,
                Material.STRIPPED_BIRCH_LOG, Material.LIGHT_BLUE_CONCRETE, Material.RED_STAINED_GLASS})
            assertTrue(BuildBattleBlockPolicy.allows(material), material.name());
        for (Material material : new Material[] {Material.CHEST, Material.BARREL, Material.SHULKER_BOX,
                Material.SPAWNER, Material.TNT, Material.SAND, Material.RED_CONCRETE_POWDER,
                Material.WATER, Material.LAVA, Material.PISTON, Material.REDSTONE_BLOCK,
                Material.OAK_LEAVES, Material.ICE, Material.FIRE})
            assertFalse(BuildBattleBlockPolicy.allows(material), material.name());
    }
}
