package com.ciaac.minecraft.minigames.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

class MinigameMenuGuardTest {
    private final UUID player = UUID.randomUUID();
    private final UUID connection = UUID.randomUUID();
    private final UUID auth = UUID.randomUUID();
    private final MinigameMenuGuard guard = new MinigameMenuGuard(player, connection, auth, "session:ACTIVE", "match:RUNNING");

    @Test void onlyOrdinaryTopInventoryClicksMayInvokeButtons() {
        assertTrue(guard.permits(guard, 0, 54, ClickType.LEFT, true, false));
        assertTrue(guard.permits(guard, 53, 54, ClickType.LEFT, true, false));
        for (ClickType click : ClickType.values()) {
            if (click != ClickType.LEFT) assertFalse(guard.permits(guard, 10, 54, click, true, false), click.toString());
        }
        assertFalse(guard.permits(guard, -999, 54, ClickType.LEFT, true, false));
        assertFalse(guard.permits(guard, 54, 54, ClickType.LEFT, true, false));
        assertFalse(guard.permits(guard, 85, 54, ClickType.LEFT, true, false));
    }

    @Test void preparedWaitingPlayersCanCancelButRecoveryAndTransitionsStayClosed() {
        var waiting = com.ciaac.minecraft.minigames.module.ModuleAvailability.WAITING;
        var preparing = com.ciaac.minecraft.minigames.runtime.SessionPhase.PREPARING;
        assertTrue(MinigameMenuGuard.allowsPhase(preparing, waiting));
        for (var availability : com.ciaac.minecraft.minigames.module.ModuleAvailability.values())
            if (availability != waiting) assertFalse(MinigameMenuGuard.allowsPhase(preparing, availability));
        for (var phase : java.util.List.of(com.ciaac.minecraft.minigames.runtime.SessionPhase.RECOVERING,
                com.ciaac.minecraft.minigames.runtime.SessionPhase.RESTORING,
                com.ciaac.minecraft.minigames.runtime.SessionPhase.QUARANTINED,
                com.ciaac.minecraft.minigames.runtime.SessionPhase.SNAPSHOTTING,
                com.ciaac.minecraft.minigames.runtime.SessionPhase.SNAPSHOT_COMMITTED))
            assertFalse(MinigameMenuGuard.allowsPhase(phase, waiting));
    }

    @Test void activeSessionsCannotMutateOtherGamesOrArenaSocialState() {
        var active = java.util.Optional.of(com.ciaac.minecraft.minigames.core.GameKey.CHECKPOINT_PARKOUR);
        assertFalse(MinigameMenuGuard.allowsAction(active, com.ciaac.minecraft.minigames.core.GameKey.ARENA, "desafiar"));
        assertTrue(MinigameMenuGuard.allowsAction(active, active.orElseThrow(), "sair"));
        assertFalse(MinigameMenuGuard.allowsAction(active, active.orElseThrow(), "entrar"));
        var arena = java.util.Optional.of(com.ciaac.minecraft.minigames.core.GameKey.ARENA);
        for (String action : java.util.List.of("grupo", "desafiar", "aceitar", "aposta", "entrar"))
            assertFalse(MinigameMenuGuard.allowsAction(arena, arena.orElseThrow(), action));
        var build = java.util.Optional.of(com.ciaac.minecraft.minigames.core.GameKey.BUILD_BATTLE);
        assertTrue(MinigameMenuGuard.allowsAction(build, build.orElseThrow(), "avaliar"));
        assertTrue(MinigameMenuGuard.allowsAction(java.util.Optional.empty(), arena.orElseThrow(), "desafiar"));
    }

    @Test void cursorAndPriorPluginDenialCannotBeBypassed() {
        assertFalse(guard.permits(guard, 10, 54, ClickType.LEFT, false, false));
        assertFalse(guard.permits(guard, 10, 54, ClickType.LEFT, true, true));
    }

    @Test void reconnectReauthenticationAndStateChangesInvalidateDeferredClicks() {
        MinigameMenuGuard[] changed = {
            new MinigameMenuGuard(UUID.randomUUID(), connection, auth, "session:ACTIVE", "match:RUNNING"),
            new MinigameMenuGuard(player, UUID.randomUUID(), auth, "session:ACTIVE", "match:RUNNING"),
            new MinigameMenuGuard(player, connection, UUID.randomUUID(), "session:ACTIVE", "match:RUNNING"),
            new MinigameMenuGuard(player, connection, auth, "session:RECOVERING", "match:RUNNING"),
            new MinigameMenuGuard(player, connection, auth, "session:ACTIVE", "other-match:RUNNING")
        };
        for (var current : changed) assertFalse(guard.permits(current, 10, 54, ClickType.LEFT, true, false));
        assertFalse(guard.permits(null, 10, 54, ClickType.LEFT, true, false));
    }
}
