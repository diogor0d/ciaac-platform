package com.ciaac.minecraft.minigames.arena;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable queue output; reservation remains an explicit next operation. */
public record QueueMatchProposal(ArenaFormat format, ArenaKitMode kitMode,
                                 TeamRoster teamA, TeamRoster teamB,
                                 List<QueueTicket> sourceTickets) {
    public QueueMatchProposal {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(kitMode, "kitMode");
        Objects.requireNonNull(teamA, "teamA");
        Objects.requireNonNull(teamB, "teamB");
        sourceTickets = List.copyOf(Objects.requireNonNull(sourceTickets, "sourceTickets"));
        if (teamA.players().size() != format.teamASize() || teamB.players().size() != format.teamBSize()) {
            throw new IllegalArgumentException("Proposal rosters do not match the format");
        }
        var participants = new LinkedHashSet<>(teamA.players());
        if (!participants.addAll(teamB.players())) {
            throw new IllegalArgumentException("Proposal teams cannot overlap");
        }
    }

    static QueueMatchProposal from(ArenaFormat format, ArenaKitMode kitMode,
                                   List<QueueTicket> first, List<QueueTicket> second) {
        var source = new ArrayList<QueueTicket>();
        source.addAll(first);
        source.addAll(second);
        return new QueueMatchProposal(format, kitMode, roster(first), roster(second), source);
    }

    private static TeamRoster roster(List<QueueTicket> tickets) {
        var players = tickets.stream().flatMap(ticket -> ticket.partyRoster().players().stream()).toList();
        return new TeamRoster(players);
    }
}
