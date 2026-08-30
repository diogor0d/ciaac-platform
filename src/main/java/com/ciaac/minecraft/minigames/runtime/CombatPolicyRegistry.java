package com.ciaac.minecraft.minigames.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class CombatPolicyRegistry {
    private final Map<UUID, CombatPolicy> byMatch = new LinkedHashMap<>();

    public synchronized void register(CombatPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        CombatPolicy previous = byMatch.putIfAbsent(policy.matchId(), policy);
        if (previous != null && !previous.equals(policy)) {
            throw new IllegalStateException("Conflicting combat policy for match " + policy.matchId());
        }
    }

    public synchronized Optional<CombatPolicy> find(UUID matchId) {
        return Optional.ofNullable(byMatch.get(Objects.requireNonNull(matchId, "matchId")));
    }

    public synchronized void remove(UUID matchId) {
        byMatch.remove(Objects.requireNonNull(matchId, "matchId"));
    }
}
