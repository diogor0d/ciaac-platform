package com.ciaac.minecraft.minigames.paper.arena;

import com.ciaac.minecraft.minigames.arena.StakedItem;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/** Read-only, deterministic item inspection for a future durable escrow adapter. */
public final class ArenaItemManifestBuilder {
    public ArenaItemManifest build(UUID owner, ArenaLoadoutSnapshot loadout, Set<String> prohibitedMaterials) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(loadout, "loadout");
        Set<String> blacklist = normalize(prohibitedMaterials);
        List<StakedItem> items = new ArrayList<>();
        Map<String, String> prohibited = new LinkedHashMap<>();
        inspect(owner, "storage", loadout.storage(), blacklist, items, prohibited);
        inspect(owner, "armor", loadout.armor(), blacklist, items, prohibited);
        inspect(owner, "extra", loadout.extra(), blacklist, items, prohibited);
        return new ArenaItemManifest(owner, items, prohibited);
    }

    private static void inspect(UUID owner, String section, ItemStack[] contents, Set<String> blacklist,
                                List<StakedItem> items, Map<String, String> prohibited) {
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.isEmpty()) continue;
            String id = section + ":" + slot;
            String material = item.getType().getKey().toString().toUpperCase(Locale.ROOT);
            String fingerprint = fingerprint(item);
            StakedItem manifestItem = new StakedItem(id, material, item.getAmount(), fingerprint);
            items.add(manifestItem);
            String shortMaterial = material.startsWith("MINECRAFT:") ? material.substring("MINECRAFT:".length()) : material;
            if (blacklist.contains(material) || blacklist.contains(shortMaterial)) {
                prohibited.put(id, "PROHIBITED_MATERIAL");
            }
        }
    }

    private static String fingerprint(ItemStack item) {
        try {
            byte[] bytes = ItemStack.serializeItemsAsBytes(new ItemStack[] { item });
            return "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException | RuntimeException exception) {
            throw new IllegalStateException("Could not fingerprint item safely", exception);
        }
    }

    private static Set<String> normalize(Set<String> values) {
        Objects.requireNonNull(values, "prohibitedMaterials");
        return values.stream().map(value -> Objects.requireNonNull(value, "prohibited material")
                        .trim().toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
