package com.ciaac.minecraft.minigames.anvildodge;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable terminal evidence for one Anvil Dodge instance. */
public record AnvilDodgeResult(
        UUID matchId,
        String rulesetRevision,
        boolean completed,
        int wavesSurvived,
        Duration elapsed,
        Map<UUID, Integer> dodges,
        Set<UUID> survivors,
        String reasonCode) {

    public AnvilDodgeResult {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(rulesetRevision, "rulesetRevision");
        if (!rulesetRevision.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}")) {
            throw new IllegalArgumentException("rulesetRevision is invalid");
        }
        if (wavesSurvived < 0) {
            throw new IllegalArgumentException("wavesSurvived cannot be negative");
        }
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed cannot be negative");
        }
        Objects.requireNonNull(dodges, "dodges");
        var copiedDodges = new LinkedHashMap<UUID, Integer>();
        dodges.forEach((player, count) -> {
            Objects.requireNonNull(player, "dodge player");
            Objects.requireNonNull(count, "dodge count");
            if (count < 0) {
                throw new IllegalArgumentException("dodge count cannot be negative");
            }
            copiedDodges.put(player, count);
        });
        dodges = Map.copyOf(copiedDodges);
        survivors = Set.copyOf(Objects.requireNonNull(survivors, "survivors"));
        if (!copiedDodges.keySet().containsAll(survivors)) {
            throw new IllegalArgumentException("survivors must belong to the result roster");
        }
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (!reasonCode.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("reasonCode is invalid");
        }
    }
}
