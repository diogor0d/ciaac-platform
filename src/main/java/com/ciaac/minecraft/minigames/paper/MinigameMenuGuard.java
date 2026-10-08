package com.ciaac.minecraft.minigames.paper;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.module.ModuleAvailability;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.inventory.ClickType;

/** Immutable ownership/state guard for deferred inventory events. */
public record MinigameMenuGuard(UUID playerId, UUID connectionId, UUID authenticationId,
                                String sessionState, String gameState) {
    public MinigameMenuGuard {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(authenticationId, "authenticationId");
        Objects.requireNonNull(sessionState, "sessionState");
        Objects.requireNonNull(gameState, "gameState");
    }

    public static boolean allowsPhase(SessionPhase phase, ModuleAvailability availability) {
        return switch (phase) {
            case RECOVERING, RESTORING, QUARANTINED, SNAPSHOTTING, SNAPSHOT_COMMITTED -> false;
            case PREPARING -> availability == ModuleAvailability.WAITING;
            default -> true;
        };
    }

    /** Active players can only leave or use the current game's bounded participation actions. */
    public static boolean allowsAction(Optional<GameKey> activeGame, GameKey requested, String action) {
        if (activeGame.isEmpty()) return true;
        if (activeGame.orElseThrow() != requested) return false;
        return action.equals("sair") || requested == GameKey.BUILD_BATTLE
                && java.util.Set.of("tema", "ver", "avaliar", "votar").contains(action);
    }

    public boolean permits(MinigameMenuGuard current, int slot, int size,
                           ClickType click, boolean cursorEmpty, boolean alreadyCancelled) {
        return equals(current) && slot >= 0 && slot < size && click == ClickType.LEFT
                && cursorEmpty && !alreadyCancelled;
    }
}
