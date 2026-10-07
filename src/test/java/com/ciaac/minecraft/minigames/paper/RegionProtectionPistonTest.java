package com.ciaac.minecraft.minigames.paper;

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
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.junit.jupiter.api.Test;

final class RegionProtectionPistonTest {
    private static final UUID WORLD_ID = UUID.randomUUID();
    private final World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
            new Class<?>[] {World.class}, (proxy, method, args) -> {
                if (method.getName().equals("getUID")) return WORLD_ID;
                throw new AssertionError("Unexpected world method: " + method.getName());
            });

    @Test
    void emptyPistonHeadCannotEnterOrLeaveAnImmutableRegion() {
        RegionProtectionListener listener = listener();
        BlockPistonExtendEvent extend = new BlockPistonExtendEvent(block(-419), List.of(), BlockFace.EAST);
        BlockPistonRetractEvent retract = new BlockPistonRetractEvent(block(-419), List.of(), BlockFace.EAST);

        listener.onPistonExtend(extend);
        listener.onPistonRetract(retract);

        assertTrue(extend.isCancelled());
        assertTrue(retract.isCancelled());
    }

    @Test
    void pistonBaseInsideImmutableRegionCannotChangeEvenWhenHeadIsOutside() {
        RegionProtectionListener listener = listener();
        BlockPistonExtendEvent event = new BlockPistonExtendEvent(block(-418), List.of(), BlockFace.WEST);

        listener.onPistonExtend(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void unrelatedEmptyPistonRemainsAllowed() {
        RegionProtectionListener listener = listener();
        BlockPistonExtendEvent extend = new BlockPistonExtendEvent(block(-421), List.of(), BlockFace.EAST);
        BlockPistonRetractEvent retract = new BlockPistonRetractEvent(block(-421), List.of(), BlockFace.EAST);

        listener.onPistonExtend(extend);
        listener.onPistonRetract(retract);

        assertFalse(extend.isCancelled());
        assertFalse(retract.isCancelled());
    }

    private RegionProtectionListener listener() {
        ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
        regions.register(new ProtectedRegion("arena-floor", GameKey.ARENA,
                new CuboidRegion(WORLD_ID, -418, -64, 6425, -321, 319, 6470),
                ProtectedRegionRole.PARTICIPANT_ONLY, true));
        return new RegionProtectionListener(regions, new RegionAdmissionRegistry(),
                new SessionRegistry(), (player, session, violation) -> {}, Clock.systemUTC());
    }

    private Block block(int x) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "getX" -> x;
                    case "getY" -> 87;
                    case "getZ" -> 6447;
                    case "getBlockData" -> Proxy.newProxyInstance(Directional.class.getClassLoader(),
                            new Class<?>[] {Directional.class}, (data, getter, arguments) -> {
                                if (getter.getName().equals("getFacing")) return BlockFace.EAST;
                                throw new AssertionError("Unexpected piston data method: " + getter.getName());
                            });
                    case "getRelative" -> block(x + ((BlockFace) args[0]).getModX());
                    default -> throw new AssertionError("Unexpected block method: " + method.getName());
                });
    }
}
