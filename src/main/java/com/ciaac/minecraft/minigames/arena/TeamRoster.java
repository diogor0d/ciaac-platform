package com.ciaac.minecraft.minigames.arena;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable roster for one side of a match. */
public record TeamRoster(List<UUID> players) {
    public TeamRoster {
        Objects.requireNonNull(players, "players");
        var copy = List.copyOf(players);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("A team must contain at least one player");
        }
        if (new LinkedHashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("A team cannot contain duplicate players");
        }
        players = copy;
    }

    public static TeamRoster of(UUID... players) {
        Objects.requireNonNull(players, "players");
        return new TeamRoster(new ArrayList<>(List.of(players)));
    }
}
