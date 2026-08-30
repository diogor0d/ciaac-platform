package com.ciaac.minecraft.minigames.knockbacksumo;

import java.time.Duration;
import java.util.Objects;

public record SumoConfig(String rulesetRevision, int minimumPlayers, int maximumPlayers, int roundsToWin, Duration roundTimeout) {
    public SumoConfig {
        if (rulesetRevision == null || rulesetRevision.isBlank()) throw new IllegalArgumentException("rulesetRevision");
        if (minimumPlayers != 2 || maximumPlayers != 2) throw new IllegalArgumentException("sumo is currently 1v1");
        if (roundsToWin < 1 || roundTimeout == null || roundTimeout.isNegative() || roundTimeout.isZero()) throw new IllegalArgumentException("invalid round rules");
    }
}
