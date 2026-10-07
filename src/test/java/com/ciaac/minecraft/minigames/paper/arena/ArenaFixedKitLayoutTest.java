package com.ciaac.minecraft.minigames.paper.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

class ArenaFixedKitLayoutTest {
    @Test
    void equipsArmorAndFirstShieldAndKeepsOtherItemsInStorage() {
        var layout = ArenaFixedKitLayout.plan(List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET), 36);

        assertEquals(List.of(0), layout.storage());
        assertEquals(2, layout.armor().get(EquipmentSlot.HEAD));
        assertEquals(3, layout.armor().get(EquipmentSlot.CHEST));
        assertEquals(4, layout.armor().get(EquipmentSlot.LEGS));
        assertEquals(5, layout.armor().get(EquipmentSlot.FEET));
        assertEquals(1, layout.offhand());
    }

    @Test
    void duplicateEquipmentSlotsStayInStorageAndOverflowIsRejected() {
        var slots = List.of(EquipmentSlot.HEAD, EquipmentSlot.HEAD, EquipmentSlot.OFF_HAND, EquipmentSlot.OFF_HAND);
        var layout = ArenaFixedKitLayout.plan(slots, 2);

        assertEquals(0, layout.armor().get(EquipmentSlot.HEAD));
        assertEquals(2, layout.offhand());
        assertEquals(List.of(1, 3), layout.storage());
        assertThrows(IllegalArgumentException.class, () -> ArenaFixedKitLayout.plan(slots, 1));
    }
}
