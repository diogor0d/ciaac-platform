package com.ciaac.minecraft.minigames.paper.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

class MinigameEventRouterTest {
    @Test
    void disconnectFailureIsReportedWithoutPreventingTheListenerBoundary() {
        List<String> failures = new ArrayList<>();

        MinigameEventRouter.attemptDisconnect(
                "sumo-disconnect", () -> { throw new IllegalStateException("internal detail"); },
                failures::add);

        assertEquals(List.of("sumo-disconnect"), failures);
    }

    @Test
    void successfulDisconnectDoesNotReportFailure() {
        List<String> failures = new ArrayList<>();
        boolean[] called = {false};

        MinigameEventRouter.attemptDisconnect(
                "parkour-disconnect", () -> called[0] = true, failures::add);

        assertEquals(List.of(), failures);
        assertTrue(called[0]);
    }

    @Test
    void reporterFailureCannotBreakLaterCleanup() {
        boolean[] laterCleanupRan = {false};

        MinigameEventRouter.attemptDisconnect(
                "archery-disconnect", () -> { throw new IllegalStateException(); },
                route -> { throw new IllegalStateException("logger unavailable"); });
        MinigameEventRouter.attemptDisconnect(
                "archery-wrapper-cleanup", () -> laterCleanupRan[0] = true,
                route -> { throw new AssertionError("must not report successful cleanup"); });

        assertTrue(laterCleanupRan[0]);
    }

    @Test
    void rightClickAirCanRouteHeldItemWithoutPretendingThereIsABlockAction() {
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] { Player.class },
                (proxy, method, arguments) -> primitiveDefault(method.getReturnType()));
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_AIR, null, null, BlockFace.SELF, EquipmentSlot.HAND);
        boolean[] routed = {false};

        assertTrue(MinigameEventRouter.heldItemUseAvailable(event));
        assertFalse(MinigameEventRouter.blockUseAvailable(event));
        assertTrue(MinigameEventRouter.routeHeldItem(event, routedEvent -> {
            routed[0] = true;
            return true;
        }));
        assertTrue(routed[0]);

        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        routed[0] = false;
        assertFalse(MinigameEventRouter.routeHeldItem(event, routedEvent -> {
            routed[0] = true;
            return true;
        }));
        assertFalse(routed[0]);
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        throw new AssertionError("Unknown primitive return type: " + type);
    }
}
