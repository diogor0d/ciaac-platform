package com.ciaac.minecraft.minigames.runtime;

import java.util.Locale;
import java.util.Objects;

public final class MachineCode {
    private MachineCode() {}

    public static String normalize(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName)
                .trim()
                .toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException(fieldName + " must be a bounded machine-readable value");
        }
        return normalized;
    }
}
