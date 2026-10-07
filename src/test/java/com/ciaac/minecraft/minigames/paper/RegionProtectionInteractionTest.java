package com.ciaac.minecraft.minigames.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.TreeType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Result;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.junit.jupiter.api.Test;

final class RegionProtectionInteractionTest {
    private static final UUID WORLD_ID = UUID.randomUUID();
    private final World world = proxy(World.class, (method, args) -> {
        if (method.equals("getUID")) return WORLD_ID;
        throw new AssertionError(method);
    });

    @Test
    void protectedBlockInteractionIsDeniedWithoutChangingItemUse() {
        var listener = listener();
        for (Action action : new Action[] {Action.RIGHT_CLICK_BLOCK, Action.LEFT_CLICK_BLOCK, Action.PHYSICAL}) {
            var event = new PlayerInteractEvent(player(), action, null, block(-418), BlockFace.UP);
            Result itemUse = event.useItemInHand();
            listener.onInteract(event);
            assertEquals(Result.DENY, event.useInteractedBlock());
            assertEquals(itemUse, event.useItemInHand());
        }
    }

    @Test
    void airAndOutsideBlockInteractionRemainUnchanged() {
        var listener = listener();
        for (Block block : new Block[] {null, block(-419)}) {
            var event = new PlayerInteractEvent(player(), Action.RIGHT_CLICK_AIR, null, block, BlockFace.UP);
            Result blockUse = event.useInteractedBlock(), itemUse = event.useItemInHand();
            listener.onInteract(event);
            assertEquals(blockUse, event.useInteractedBlock());
            assertEquals(itemUse, event.useItemInHand());
        }
    }

    @Test
    void entityPhysicalInteractionAndFireOrFormationAreDeniedOnlyInside() {
        var listener = listener();
        for (int x : new int[] {-418, -419}) {
            Block block = block(x);
            Entity entity = proxy(Entity.class, (method, args) -> { throw new AssertionError(method); });
            var pressure = new EntityInteractEvent(entity, block);
            var fire = new BlockIgniteEvent(block, BlockIgniteEvent.IgniteCause.ARROW, entity);
            BlockState state = proxy(BlockState.class, (method, args) -> { throw new AssertionError(method); });
            var form = new BlockFormEvent(block, state);
            listener.onEntityInteract(pressure);
            listener.onIgnite(fire);
            listener.onForm(form);
            if (x == -418) {
                assertTrue(pressure.isCancelled()); assertTrue(fire.isCancelled()); assertTrue(form.isCancelled());
            } else {
                assertFalse(pressure.isCancelled()); assertFalse(fire.isCancelled()); assertFalse(form.isCancelled());
            }
        }
    }

    @Test
    void protectedRedstoneCannotTurnOnOrOffButOutsidePowerCanChange() {
        var listener = listener();
        for (int old : new int[] {0, 15}) {
            var inside = new BlockRedstoneEvent(block(-418), old, 15 - old);
            var outside = new BlockRedstoneEvent(block(-419), old, 15 - old);
            listener.onRedstone(inside); listener.onRedstone(outside);
            assertEquals(old, inside.getNewCurrent());
            assertEquals(15 - old, outside.getNewCurrent());
        }
    }

    @Test
    void growthStartingOutsideCannotWriteAcrossTheProtectedBoundary() {
        var listener = listener();
        for (int destination : new int[] {-418, -420}) {
            BlockState state = proxy(BlockState.class, (method, args) -> {
                if (method.equals("getBlock")) return block(destination);
                throw new AssertionError(method);
            });
            var fertilize = new BlockFertilizeEvent(block(-419), null, List.of(state));
            var structure = new StructureGrowEvent(new Location(world, -419, 87, 6447),
                    TreeType.TREE, true, null, List.of(state));
            listener.onFertilize(fertilize); listener.onStructureGrow(structure);
            assertEquals(destination == -418, fertilize.isCancelled());
            assertEquals(destination == -418, structure.isCancelled());
        }
    }

    private RegionProtectionListener listener() {
        var regions = new ProtectedRegionRegistry();
        regions.register(new ProtectedRegion("arena-floor", GameKey.ARENA,
                new CuboidRegion(WORLD_ID, -418, -64, 6425, -321, 319, 6470),
                ProtectedRegionRole.PARTICIPANT_ONLY, true));
        return new RegionProtectionListener(regions, new RegionAdmissionRegistry(), new SessionRegistry(),
                (player, session, violation) -> {}, Clock.systemUTC());
    }

    private Player player() {
        return proxy(Player.class, (method, args) -> { throw new AssertionError(method); });
    }

    private Block block(int x) {
        return proxy(Block.class, (method, args) -> switch (method) {
            case "getWorld" -> world;
            case "getX" -> x;
            case "getY" -> 87;
            case "getZ" -> 6447;
            default -> throw new AssertionError(method);
        });
    }

    private interface Call { Object invoke(String method, Object[] args); }

    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (instance, method, args) -> call.invoke(method.getName(), args)));
    }
}
