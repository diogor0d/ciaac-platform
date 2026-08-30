package com.ciaac.minecraft.minigames.paper.arena;

import com.ciaac.minecraft.minigames.arena.ArenaPhase;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Snapshot for `/coliseu estado`; contains no player-controlled text. */
public record ArenaStatus(boolean enabled, int queuedPlayers, Optional<ArenaPhase> phase,
                          Optional<UUID> matchId, String messagePtPt) {
    public ArenaStatus {
        phase = Objects.requireNonNull(phase, "phase");
        matchId = Objects.requireNonNull(matchId, "matchId");
        messagePtPt = Objects.requireNonNull(messagePtPt, "messagePtPt");
        if (queuedPlayers < 0 || messagePtPt.isBlank() || messagePtPt.length() > 300) {
            throw new IllegalArgumentException("Arena status is invalid");
        }
    }
}
