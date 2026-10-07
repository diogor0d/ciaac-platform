package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class InventoryFacetHandler implements FacetSnapshotHandler {
    private static final int VERSION = 1;
    private static final int STORAGE_SIZE = 36;
    private static final int ARMOR_SIZE = 4;
    // Paper 26.2 includes off-hand, body and saddle, even for a player.
    private static final int EXTRA_SIZE = 3;
    private static final int ENDER_CHEST_SIZE = 27;
    private static final Set<PlayerStateFacet> FACETS = Set.of(
            PlayerStateFacet.INVENTORY,
            PlayerStateFacet.ITEM_METADATA_AND_DURABILITY,
            PlayerStateFacet.ARMOR,
            PlayerStateFacet.OFF_HAND,
            PlayerStateFacet.CURSOR,
            PlayerStateFacet.ENDER_CHEST);

    @Override
    public Set<PlayerStateFacet> facets() {
        return FACETS;
    }

    @Override
    public byte[] capture(Player player) {
        try {
            PlayerInventory inventory = player.getInventory();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                BinaryStateIo.writeBytes(output, ItemStack.serializeItemsAsBytes(inventory.getStorageContents()));
                BinaryStateIo.writeBytes(output, ItemStack.serializeItemsAsBytes(inventory.getArmorContents()));
                BinaryStateIo.writeBytes(output, ItemStack.serializeItemsAsBytes(inventory.getExtraContents()));
                BinaryStateIo.writeBytes(output, ItemStack.serializeItemsAsBytes(player.getEnderChest().getContents()));
                BinaryStateIo.writeBytes(output, ItemStack.serializeItemsAsBytes(
                        new ItemStack[] { player.getItemOnCursor() }));
                output.writeInt(inventory.getHeldItemSlot());
            }
            byte[] payload = bytes.toByteArray();
            decode(payload);
            return payload;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode player inventory", exception);
        }
    }

    @Override
    public void validateRestore(byte[] payload) {
        decode(payload);
    }

    @Override
    public void enterTemporaryState(Player player) {
        clear(player);
    }

    @Override
    public void purgeTemporaryState(Player player) {
        clear(player);
    }

    @Override
    public void restore(Player player, byte[] payload) {
        Decoded decoded = decode(payload);
        clear(player);
        PlayerInventory inventory = player.getInventory();
        inventory.setStorageContents(decoded.storage());
        inventory.setArmorContents(decoded.armor());
        inventory.setExtraContents(decoded.extra());
        player.getEnderChest().setContents(decoded.ender());
        player.setItemOnCursor(decoded.cursor()[0]);
        inventory.setHeldItemSlot(decoded.heldSlot());
    }

    private static Decoded decode(byte[] payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException("Unsupported inventory snapshot version");
            }
            ItemStack[] storage = ItemStack.deserializeItemsFromBytes(BinaryStateIo.readBytes(input));
            ItemStack[] armor = ItemStack.deserializeItemsFromBytes(BinaryStateIo.readBytes(input));
            ItemStack[] extra = ItemStack.deserializeItemsFromBytes(BinaryStateIo.readBytes(input));
            ItemStack[] ender = ItemStack.deserializeItemsFromBytes(BinaryStateIo.readBytes(input));
            ItemStack[] cursor = ItemStack.deserializeItemsFromBytes(BinaryStateIo.readBytes(input));
            int heldSlot = input.readInt();
            BinaryStateIo.requireExhausted(input);
            if (storage.length != STORAGE_SIZE || armor.length != ARMOR_SIZE || extra.length != EXTRA_SIZE
                    || ender.length != ENDER_CHEST_SIZE || cursor.length != 1
                    || heldSlot < 0 || heldSlot > 8) {
                throw new IllegalArgumentException("Inventory snapshot dimensions are invalid");
            }
            return new Decoded(storage, armor, extra, ender, cursor, heldSlot);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Could not restore player inventory snapshot", exception);
        }
    }

    private record Decoded(
            ItemStack[] storage,
            ItemStack[] armor,
            ItemStack[] extra,
            ItemStack[] ender,
            ItemStack[] cursor,
            int heldSlot) {}

    private static void clear(Player player) {
        player.closeInventory();
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setExtraContents(new ItemStack[player.getInventory().getExtraContents().length]);
        player.getEnderChest().clear();
        player.setItemOnCursor(null);
        player.updateInventory();
    }
}
