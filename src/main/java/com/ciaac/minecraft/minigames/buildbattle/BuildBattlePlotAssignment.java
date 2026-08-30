package com.ciaac.minecraft.minigames.buildbattle;

import java.util.Objects;
import java.util.UUID;

/** Immutable ownership record created when the build phase starts. */
public record BuildBattlePlotAssignment(BuildBattlePlot plot, UUID owner) {
    public BuildBattlePlotAssignment {
        Objects.requireNonNull(plot, "plot");
        Objects.requireNonNull(owner, "owner");
    }
}
