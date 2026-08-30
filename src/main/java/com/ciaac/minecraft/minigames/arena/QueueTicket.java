package com.ciaac.minecraft.minigames.arena;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Logical queue data only; it deliberately has no location or teleport state. */
public record QueueTicket(
        UUID ticketId,
        UUID playerId,
        UUID partyId,
        TeamRoster partyRoster,
        ArenaFormat format,
        ArenaKitMode kitMode,
        Instant queuedAt) {
    public QueueTicket {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(partyRoster, "partyRoster");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(kitMode, "kitMode");
        Objects.requireNonNull(queuedAt, "queuedAt");
        if (!partyRoster.players().contains(playerId)) {
            throw new IllegalArgumentException("Ticket player must belong to its immutable party roster");
        }
        if (partyRoster.players().size() > Math.max(format.teamASize(), format.teamBSize())) {
            throw new IllegalArgumentException("Party roster cannot fit either side of the selected format");
        }
        if (kitMode == ArenaKitMode.STAKED_SURVIVAL && !format.equals(ArenaFormat.standard(1))) {
            throw new IllegalArgumentException("Staked queue tickets are limited to 1v1");
        }
    }

    public static QueueTicket enqueue(UUID playerId, ArenaFormat format, Instant queuedAt) {
        return enqueue(playerId, playerId, TeamRoster.of(playerId), format, ArenaKitMode.FIXED, queuedAt);
    }

    public static QueueTicket enqueue(UUID playerId, UUID partyId, TeamRoster partyRoster,
                                      ArenaFormat format, ArenaKitMode kitMode, Instant queuedAt) {
        return new QueueTicket(UUID.randomUUID(), playerId, partyId, partyRoster, format, kitMode, queuedAt);
    }
}
