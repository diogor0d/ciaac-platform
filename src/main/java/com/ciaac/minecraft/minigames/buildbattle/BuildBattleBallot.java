package com.ciaac.minecraft.minigames.buildbattle;

import java.util.Objects;
import java.util.UUID;

/** One accepted vote. A ballot is immutable and uniquely identified by voter and plot. */
public record BuildBattleBallot(UUID voter, BuildBattlePlot plot, int score) {
    public BuildBattleBallot {
        Objects.requireNonNull(voter, "voter");
        Objects.requireNonNull(plot, "plot");
    }
}
