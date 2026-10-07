package local.harness;

import com.ciaac.minecraft.minigames.paper.isolation.InventoryFacetHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;

/** Real Paper item serialization with synthetic inventory holders, not a player session. */
final class InventorySnapshotProbe {
    static void verify() {
        Map<String, Object> state = new HashMap<>();
        ItemStack[] storage = new ItemStack[36];
        storage[0] = new ItemStack(Material.DIAMOND_SWORD);
        var meta = storage[0].getItemMeta();
        ((Damageable)meta).setDamage(7);
        storage[0].setItemMeta(meta);
        ItemStack[] armor = {new ItemStack(Material.IRON_BOOTS), null, null, null};
        ItemStack[] extra = {new ItemStack(Material.SHIELD), new ItemStack(Material.ELYTRA), new ItemStack(Material.SADDLE)};
        ItemStack[] ender = new ItemStack[27];
        ender[26] = new ItemStack(Material.EMERALD, 3);
        state.put("StorageContents", storage);state.put("ArmorContents", armor);state.put("ExtraContents", extra);
        state.put("Contents", ender);state.put("ItemOnCursor", new ItemStack(Material.COMPASS));state.put("HeldItemSlot", 5);
        PlayerInventory inventory = proxy(PlayerInventory.class, state);
        Inventory chest = proxy(Inventory.class, state);
        Player player = (Player)Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (self, method, args) -> switch(method.getName()) {
                    case "getInventory" -> inventory;
                    case "getEnderChest" -> chest;
                    case "getItemOnCursor" -> state.get("ItemOnCursor");
                    case "setItemOnCursor" -> {state.put("ItemOnCursor", args[0]);yield null;}
                    case "closeInventory", "updateInventory" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var handler = new InventoryFacetHandler();
        byte[] payload = handler.capture(player);
        handler.validateRestore(payload);
        handler.enterTemporaryState(player);
        handler.restore(player, payload);
        require(sameItems(storage, (ItemStack[])state.get("StorageContents")), "Storage or item damage changed");
        require(sameItems(armor, (ItemStack[])state.get("ArmorContents")), "Armor changed");
        require(sameItems(extra, (ItemStack[])state.get("ExtraContents")), "Off-hand/body/saddle order changed");
        require(sameItems(ender, (ItemStack[])state.get("Contents")), "Ender chest changed");
        require(new ItemStack(Material.COMPASS).equals(state.get("ItemOnCursor")), "Cursor changed");
        require(Integer.valueOf(5).equals(state.get("HeldItemSlot")), "Held slot changed");
        state.put("ExtraContents", new ItemStack[1]);
        try {handler.capture(player);throw new IllegalStateException("Unsupported extra-slot layout accepted");}
        catch (IllegalArgumentException expected) { }
    }

    private static <T> T proxy(Class<T> type, Map<String, Object> state) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            String name = method.getName();
            if(name.startsWith("get") && state.containsKey(name.substring(3)))return state.get(name.substring(3));
            if(name.startsWith("set")){state.put(name.substring(3),args[0]);return null;}
            if(name.equals("clear"))return null;
            throw new UnsupportedOperationException(name);
        }));
    }

    private static boolean sameItems(ItemStack[] expected, ItemStack[] actual) {
        if(expected.length!=actual.length)return false;
        for(int i=0;i<expected.length;i++) {
            // Paper's native item codec represents a null empty slot as AIR.
            boolean emptyExpected=expected[i]==null || expected[i].isEmpty();
            boolean emptyActual=actual[i]==null || actual[i].isEmpty();
            if(emptyExpected!=emptyActual || (!emptyExpected && !expected[i].equals(actual[i])))return false;
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if(!condition)throw new IllegalStateException(message);
    }
}
