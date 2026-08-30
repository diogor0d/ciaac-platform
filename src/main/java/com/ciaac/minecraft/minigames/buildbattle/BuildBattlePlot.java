package com.ciaac.minecraft.minigames.buildbattle;

import java.util.Objects;

/** Stable identifier for a plot in the plugin-owned arena template. */
public record BuildBattlePlot(String id) {
    private static final int MAX_ID_LENGTH = 32;

    public BuildBattlePlot {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || id.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("plot id must be non-blank and at most " + MAX_ID_LENGTH + " characters");
        }
    }
}
