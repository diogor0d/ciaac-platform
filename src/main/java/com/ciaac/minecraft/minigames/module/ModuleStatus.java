package com.ciaac.minecraft.minigames.module;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.Objects;
import java.util.OptionalInt;

/** Small immutable view intentionally independent from each game's state machine. */
public record ModuleStatus(
        GameKey game,
        ModuleAvailability availability,
        boolean joinable,
        int participants,
        OptionalInt capacity,
        String messagePtPt) {

    public ModuleStatus {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(capacity, "capacity");
        if (participants < 0) throw new IllegalArgumentException("participants must not be negative");
        if (capacity.isPresent() && capacity.getAsInt() < participants) {
            throw new IllegalArgumentException("capacity must not be below participants");
        }
        if (messagePtPt == null || messagePtPt.isBlank() || messagePtPt.length() > 160) {
            throw new IllegalArgumentException("messagePtPt must contain 1-160 characters");
        }
        if (joinable && availability != ModuleAvailability.WAITING) {
            throw new IllegalArgumentException("Only waiting modules may accept joins");
        }
    }

    public static ModuleStatus closed(GameKey game, String messagePtPt) {
        return new ModuleStatus(
                game, ModuleAvailability.CLOSED, false, 0, OptionalInt.empty(), messagePtPt);
    }
}
