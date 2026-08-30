package com.ciaac.minecraft.minigames.arena;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable public view of a short-lived Coliseum party. */
public record ArenaParty(UUID partyId, UUID leader, List<UUID> members, Instant createdAt) {
    public static final int MAX_MEMBERS = 3;

    public ArenaParty {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(leader, "leader");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        Objects.requireNonNull(createdAt, "createdAt");
        if (members.isEmpty() || members.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException("A Coliseum party needs one to three members");
        }
        if (!members.getFirst().equals(leader) || members.stream().distinct().count() != members.size()) {
            throw new IllegalArgumentException("The leader must be the first unique party member");
        }
    }

    public TeamRoster roster() {
        return new TeamRoster(members);
    }
}
