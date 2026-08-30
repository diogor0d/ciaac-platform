package com.ciaac.minecraft.minigames.paper.arena.staked;

import com.ciaac.minecraft.minigames.paper.arena.ArenaLoadoutSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Main-thread live-inventory boundary for durable staked claims.
 *
 * <p>It never drops an item. Delivery begins durably only after this adapter
 * proves that the complete payload fits. Any unexpected mutation failure is
 * intentionally left in the repository's DELIVERING recovery state.</p>
 */
public final class StakedInventoryAdapter {
    public StakedInventoryPayload capture(Player player, String manifestDigest) {
        requireMainThread(player);
        return StakedInventoryPayload.capture(player.getUniqueId(), manifestDigest,
                ArenaLoadoutSnapshot.capture(player));
    }

    public void withdrawExact(Player player, StakedInventoryPayload expected) {
        requireMainThread(player);
        Objects.requireNonNull(expected, "expected");
        if (!player.getUniqueId().equals(expected.playerId())
                || !capture(player, expected.manifestDigest()).exactlyMatches(expected)) {
            throw new IllegalStateException("STAKED_INVENTORY_CHANGED");
        }
        player.getInventory().setStorageContents(new ItemStack[
                player.getInventory().getStorageContents().length]);
        player.getInventory().setArmorContents(new ItemStack[
                player.getInventory().getArmorContents().length]);
        player.getInventory().setExtraContents(new ItemStack[
                player.getInventory().getExtraContents().length]);
        player.updateInventory();
        if (!inventoryEmpty(player)) throw new IllegalStateException("STAKED_WITHDRAWAL_INCOMPLETE");
    }

    public boolean canDeliver(Player beneficiary, StakedClaim claim,
                              StakedInventoryPayload payload) {
        requireMainThread(beneficiary);
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(payload, "payload");
        if (!claim.beneficiaryId().equals(beneficiary.getUniqueId())
                || !claim.sourcePlayerId().equals(payload.playerId())
                || !claim.payloadDigest().equals(payload.payloadSha256())) return false;
        if (claim.sourcePlayerId().equals(claim.beneficiaryId()) && inventoryEmpty(beneficiary)) {
            return layoutFits(beneficiary, payload);
        }
        return simulateStorageMerge(beneficiary.getInventory().getStorageContents(), flattened(payload));
    }

    public void deliver(Player beneficiary, StakedClaim claim, StakedInventoryPayload payload) {
        if (!canDeliver(beneficiary, claim, payload)) {
            throw new IllegalStateException("STAKED_CLAIM_DOES_NOT_FIT");
        }
        if (claim.sourcePlayerId().equals(claim.beneficiaryId()) && inventoryEmpty(beneficiary)) {
            decode(payload).apply(beneficiary);
            return;
        }
        for (ItemStack item : flattened(payload)) {
            Map<Integer, ItemStack> leftovers = beneficiary.getInventory().addItem(item.clone());
            if (!leftovers.isEmpty()) {
                throw new IllegalStateException("STAKED_DELIVERY_BECAME_PARTIAL");
            }
        }
        beneficiary.updateInventory();
    }

    public ArenaLoadoutSnapshot decode(StakedInventoryPayload payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            return new ArenaLoadoutSnapshot(
                    ItemStack.deserializeItemsFromBytes(payload.storageBytes()),
                    ItemStack.deserializeItemsFromBytes(payload.armorBytes()),
                    ItemStack.deserializeItemsFromBytes(payload.extraBytes()));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("STAKED_PAYLOAD_DESERIALIZATION_FAILED", failure);
        }
    }

    private boolean layoutFits(Player player, StakedInventoryPayload payload) {
        ArenaLoadoutSnapshot decoded = decode(payload);
        return decoded.storage().length == player.getInventory().getStorageContents().length
                && decoded.armor().length == player.getInventory().getArmorContents().length
                && decoded.extra().length == player.getInventory().getExtraContents().length;
    }

    private List<ItemStack> flattened(StakedInventoryPayload payload) {
        ArenaLoadoutSnapshot decoded = decode(payload);
        List<ItemStack> items = new ArrayList<>();
        append(items, decoded.storage());
        append(items, decoded.armor());
        append(items, decoded.extra());
        return List.copyOf(items);
    }

    private static void append(List<ItemStack> target, ItemStack[] source) {
        Arrays.stream(source).filter(item -> item != null && !item.isEmpty())
                .map(ItemStack::clone).forEach(target::add);
    }

    private static boolean simulateStorageMerge(ItemStack[] current, List<ItemStack> incoming) {
        ItemStack[] simulated = Arrays.stream(current)
                .map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        for (ItemStack original : incoming) {
            ItemStack remaining = original.clone();
            for (ItemStack slot : simulated) {
                if (slot == null || !slot.isSimilar(remaining)) continue;
                int capacity = Math.max(0, Math.min(slot.getMaxStackSize(), 64) - slot.getAmount());
                int moved = Math.min(capacity, remaining.getAmount());
                slot.setAmount(slot.getAmount() + moved);
                remaining.setAmount(remaining.getAmount() - moved);
                if (remaining.getAmount() == 0) break;
            }
            while (remaining.getAmount() > 0) {
                int empty = firstEmpty(simulated);
                if (empty < 0) return false;
                int moved = Math.min(Math.min(remaining.getMaxStackSize(), 64), remaining.getAmount());
                ItemStack placed = remaining.clone();
                placed.setAmount(moved);
                simulated[empty] = placed;
                remaining.setAmount(remaining.getAmount() - moved);
            }
        }
        return true;
    }

    private static int firstEmpty(ItemStack[] contents) {
        for (int index = 0; index < contents.length; index++) {
            if (contents[index] == null || contents[index].isEmpty()) return index;
        }
        return -1;
    }

    private static boolean inventoryEmpty(Player player) {
        return Arrays.stream(player.getInventory().getStorageContents())
                .allMatch(item -> item == null || item.isEmpty())
                && Arrays.stream(player.getInventory().getArmorContents())
                .allMatch(item -> item == null || item.isEmpty())
                && Arrays.stream(player.getInventory().getExtraContents())
                .allMatch(item -> item == null || item.isEmpty())
                && (player.getItemOnCursor() == null || player.getItemOnCursor().isEmpty());
    }

    private static void requireMainThread(Player player) {
        Objects.requireNonNull(player, "player");
        if (!player.getServer().isPrimaryThread()) {
            throw new IllegalStateException("MAIN_THREAD_REQUIRED");
        }
    }
}
