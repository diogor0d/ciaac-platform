package com.ciaac.minecraft.minigames.module;

/** Bounded player-facing result. Machine codes are suitable for local audit events. */
public record ModuleActionResult(boolean accepted, String code, String messagePtPt) {
    public ModuleActionResult {
        if (code == null || !code.matches("[A-Z0-9_]{2,64}")) {
            throw new IllegalArgumentException("Invalid module action code");
        }
        if (messagePtPt == null || messagePtPt.isBlank() || messagePtPt.length() > 300) {
            throw new IllegalArgumentException("messagePtPt must contain 1-300 characters");
        }
    }

    public static ModuleActionResult accepted(String code, String messagePtPt) {
        return new ModuleActionResult(true, code, messagePtPt);
    }

    public static ModuleActionResult rejected(String code, String messagePtPt) {
        return new ModuleActionResult(false, code, messagePtPt);
    }
}
