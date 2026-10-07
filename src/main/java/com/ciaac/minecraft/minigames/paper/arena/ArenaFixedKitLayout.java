package com.ciaac.minecraft.minigames.paper.arena;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Deterministic placement of configured fixed-kit items into usable equipment slots. */
final class ArenaFixedKitLayout {
    record Layout(List<ItemStack> storage, Map<EquipmentSlot, ItemStack> armor, ItemStack offhand) {
        Layout {
            storage = List.copyOf(storage);
            armor = Map.copyOf(armor);
        }
    }

    private ArenaFixedKitLayout() {}

    record SlotPlan(List<Integer> storage, Map<EquipmentSlot, Integer> armor, Integer offhand) {}

    static Layout arrange(List<ItemStack> items, int storageCapacity) {
        Objects.requireNonNull(items, "items");
        List<ItemStack> copies = items.stream().map(item -> Objects.requireNonNull(item, "item do kit").clone()).toList();
        SlotPlan plan = plan(copies.stream().map(item -> item.getType() == Material.SHIELD
                ? EquipmentSlot.OFF_HAND : item.getType().getEquipmentSlot()).toList(), storageCapacity);
        Map<EquipmentSlot, ItemStack> armor = new EnumMap<>(EquipmentSlot.class);
        plan.armor().forEach((slot, index) -> armor.put(slot, copies.get(index)));
        return new Layout(plan.storage().stream().map(copies::get).toList(), armor,
                plan.offhand() == null ? null : copies.get(plan.offhand()));
    }

    static SlotPlan plan(List<EquipmentSlot> slots, int storageCapacity) {
        if (storageCapacity < 0) throw new IllegalArgumentException("A capacidade do inventário é inválida.");
        List<Integer> storage = new ArrayList<>();
        Map<EquipmentSlot, Integer> armor = new EnumMap<>(EquipmentSlot.class);
        Integer offhand = null;
        for (int index = 0; index < slots.size(); index++) {
            EquipmentSlot candidate = Objects.requireNonNull(slots.get(index), "slot do kit");
            if (candidate == EquipmentSlot.OFF_HAND && offhand == null) {
                offhand = index;
                continue;
            }
            EquipmentSlot slot = armorSlot(candidate);
            if (slot != null && !armor.containsKey(slot)) {
                armor.put(slot, index);
                continue;
            }
            storage.add(index);
        }
        if (storage.size() > storageCapacity) {
            throw new IllegalArgumentException("O kit fixo excede a capacidade do inventário.");
        }
        return new SlotPlan(List.copyOf(storage), Map.copyOf(armor), offhand);
    }

    private static EquipmentSlot armorSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD, CHEST, LEGS, FEET -> slot;
            default -> null;
        };
    }
}
