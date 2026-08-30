package com.ciaac.minecraft.minigames.paper.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ArenaItemManifestBuilderTest {
    @Test
    void fingerprintsRetainProhibitedItemsWithoutMutatingTheLoadout() {
        Assumptions.assumeTrue(Bukkit.getServer() != null,
                "Paper registry bootstrap is required for ItemStack serialization");
        UUID owner = UUID.randomUUID();
        ItemStack diamond = new ItemStack(Material.DIAMOND, 2);
        ArenaLoadoutSnapshot loadout = new ArenaLoadoutSnapshot(new ItemStack[] { diamond }, new ItemStack[0],
                new ItemStack[0]);
        ArenaItemManifest manifest = new ArenaItemManifestBuilder().build(owner, loadout, Set.of("MINECRAFT:DIAMOND"));

        assertFalse(manifest.admissible());
        assertEquals(1, manifest.items().size());
        assertEquals(1, manifest.prohibitedItems().size());
        assertNotSame(diamond, loadout.storage()[0]);
    }
}
