package com.ciaac.minecraft.minigames.isolation;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record IsolationPolicy(Set<PlayerStateFacet> protectedFacets) {
    public IsolationPolicy {
        Objects.requireNonNull(protectedFacets, "protectedFacets");
        protectedFacets = Set.copyOf(protectedFacets);
        if (!protectedFacets.containsAll(EnumSet.allOf(PlayerStateFacet.class))) {
            throw new IllegalArgumentException(
                    "Strict minigame isolation must cover every declared player-state facet");
        }
    }

    public static IsolationPolicy strictNoProgress() {
        return new IsolationPolicy(EnumSet.allOf(PlayerStateFacet.class));
    }
}
