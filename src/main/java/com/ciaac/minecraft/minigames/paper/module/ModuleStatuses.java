package com.ciaac.minecraft.minigames.paper.module;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.ModuleAvailability;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import java.util.Objects;
import java.util.OptionalInt;

/** Maps game-specific phases to the shared command/display status contract. */
final class ModuleStatuses {
    private ModuleStatuses() {}

    static ModuleStatus of(GameKey game, boolean enabled, String phase, boolean joinable,
                           int participants, OptionalInt capacity, String messagePtPt) {
        Objects.requireNonNull(game, "game");
        ModuleAvailability availability = availability(enabled, phase);
        return new ModuleStatus(game, availability, joinable && availability == ModuleAvailability.WAITING,
                participants, Objects.requireNonNull(capacity, "capacity"), boundedMessage(messagePtPt));
    }

    static ModuleStatus unavailable(GameKey game) {
        return ModuleStatus.closed(Objects.requireNonNull(game, "game"),
                "Este minijogo está temporariamente indisponível.");
    }

    static ModuleAvailability availability(boolean enabled, String phase) {
        if (!enabled) return ModuleAvailability.CLOSED;
        String value = Objects.requireNonNull(phase, "phase").trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "IDLE", "WAITING" -> ModuleAvailability.WAITING;
            case "COUNTDOWN", "ENTRY_LOCKED", "ADMITTING", "RESERVED_READY", "THEME_VOTING" -> ModuleAvailability.STARTING;
            case "BUILDING", "RUNNING", "ACTIVE", "REACTION", "RESOLVING" -> ModuleAvailability.RUNNING;
            case "VOTING" -> ModuleAvailability.VOTING;
            case "RESULTS", "FINISHING", "RESTORING" -> ModuleAvailability.FINISHING;
            case "RESETTING", "RECOVERING", "RECOVERY" -> ModuleAvailability.RECOVERY;
            case "CLOSED", "DISABLED" -> ModuleAvailability.CLOSED;
            default -> ModuleAvailability.CLOSED;
        };
    }

    private static String boundedMessage(String value) {
        Objects.requireNonNull(value, "messagePtPt");
        if (value.isBlank()) throw new IllegalArgumentException("messagePtPt must not be blank");
        return value.length() <= 160 ? value : value.substring(0, 157) + "...";
    }
}
