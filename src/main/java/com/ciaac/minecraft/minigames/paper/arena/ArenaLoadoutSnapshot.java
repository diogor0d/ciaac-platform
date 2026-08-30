package com.ciaac.minecraft.minigames.paper.arena;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Detached copy of the selected survival loadout; never exposes live inventory arrays. */
public record ArenaLoadoutSnapshot(ItemStack[] storage, ItemStack[] armor, ItemStack[] extra) {
    public ArenaLoadoutSnapshot {
        storage = copy(storage, "storage");
        armor = copy(armor, "armor");
        extra = copy(extra, "extra");
    }

    public static ArenaLoadoutSnapshot capture(Player player) {
        Objects.requireNonNull(player, "player");
        return new ArenaLoadoutSnapshot(player.getInventory().getStorageContents(),
                player.getInventory().getArmorContents(), player.getInventory().getExtraContents());
    }

    @Override
    public ItemStack[] storage() { return copy(storage, "storage"); }

    @Override
    public ItemStack[] armor() { return copy(armor, "armor"); }

    @Override
    public ItemStack[] extra() { return copy(extra, "extra"); }

    public void apply(Player player) {
        Objects.requireNonNull(player, "player");
        player.getInventory().setStorageContents(copy(storage, "storage"));
        player.getInventory().setArmorContents(copy(armor, "armor"));
        player.getInventory().setExtraContents(copy(extra, "extra"));
        player.updateInventory();
    }

    /** Returns a detached combat copy with prohibited stacks withheld. */
    public ArenaLoadoutSnapshot withoutMaterials(Set<String> prohibitedMaterials) {
        Set<String> prohibited = Set.copyOf(Objects.requireNonNull(prohibitedMaterials, "prohibitedMaterials"));
        return new ArenaLoadoutSnapshot(
                filtered(storage, prohibited), filtered(armor, prohibited), filtered(extra, prohibited));
    }

    /** Counts item units withheld from the temporary combat copy, not the survival snapshot. */
    public int prohibitedItemCount(Set<String> prohibitedMaterials) {
        Set<String> prohibited = Set.copyOf(Objects.requireNonNull(prohibitedMaterials, "prohibitedMaterials"));
        return count(storage, prohibited) + count(armor, prohibited) + count(extra, prohibited);
    }

    private static ItemStack[] copy(ItemStack[] values, String name) {
        Objects.requireNonNull(values, name);
        return Arrays.stream(values).map(value -> value == null ? null : value.clone()).toArray(ItemStack[]::new);
    }

    private static ItemStack[] filtered(ItemStack[] values, Set<String> prohibited) {
        return Arrays.stream(values)
                .map(value -> prohibited(value, prohibited) ? null : value == null ? null : value.clone())
                .toArray(ItemStack[]::new);
    }

    private static int count(ItemStack[] values, Set<String> prohibited) {
        return Arrays.stream(values).filter(value -> prohibited(value, prohibited))
                .mapToInt(ItemStack::getAmount).sum();
    }

    private static boolean prohibited(ItemStack item, Set<String> prohibited) {
        if (item == null || item.isEmpty()) return false;
        String name = item.getType().name();
        String key = item.getType().getKey().toString().toUpperCase(java.util.Locale.ROOT);
        return prohibited.contains(name) || prohibited.contains(key);
    }
}
