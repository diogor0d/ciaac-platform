package com.ciaac.minecraft.minigames.hotpotato;
import java.time.Instant; import java.util.*;
public record HotPotatoOutcome(UUID matchId, String kind, Set<UUID> participants, Instant finishedAt, String reason) {
    public HotPotatoOutcome { Objects.requireNonNull(matchId); kind=Objects.requireNonNull(kind); participants=Set.copyOf(participants); finishedAt=Objects.requireNonNull(finishedAt); reason=Objects.requireNonNull(reason); }
}
