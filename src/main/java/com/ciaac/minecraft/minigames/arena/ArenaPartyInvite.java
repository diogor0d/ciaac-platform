package com.ciaac.minecraft.minigames.arena;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One leader-bound, target-bound, expiring party invitation. */
public record ArenaPartyInvite(
        UUID inviteId,
        UUID partyId,
        UUID leader,
        UUID target,
        Instant createdAt,
        Instant expiresAt) {

    public ArenaPartyInvite {
        Objects.requireNonNull(inviteId, "inviteId");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(leader, "leader");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (leader.equals(target) || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Invalid Coliseum party invitation");
        }
    }

    public boolean validAt(Instant instant) {
        return !Objects.requireNonNull(instant, "instant").isAfter(expiresAt);
    }
}
