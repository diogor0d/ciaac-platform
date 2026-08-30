package com.ciaac.minecraft.minigames.buildbattle;

import java.util.Objects;

/** A bounded, display-safe theme selected for one match. */
public record BuildBattleTheme(String id, String displayName) {
    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_DISPLAY_NAME_LENGTH = 100;

    public BuildBattleTheme {
        id = requireBounded("id", id, MAX_ID_LENGTH);
        displayName = requireBounded("displayName", displayName, MAX_DISPLAY_NAME_LENGTH);
    }

    private static String requireBounded(String field, String value, int maxLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + maxLength + " characters");
        }
        return value;
    }
}
