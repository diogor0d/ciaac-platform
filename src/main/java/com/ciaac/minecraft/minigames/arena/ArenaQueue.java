package com.ciaac.minecraft.minigames.arena;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One logical queue owner; it never splits a party across opposing teams. */
public final class ArenaQueue {
    private final ArenaFormatPolicy policy;
    private final List<QueueTicket> tickets = new ArrayList<>();

    public ArenaQueue() { this(ArenaFormatPolicy.defaultPolicy()); }

    public ArenaQueue(ArenaFormatPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public synchronized QueueTicket enqueue(QueueTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        policy.requireSupported(ticket.format());
        if (tickets.stream().anyMatch(existing -> existing.partyId().equals(ticket.partyId()))) {
            throw new IllegalStateException("A party already has an arena queue ticket");
        }
        tickets.add(ticket);
        return ticket;
    }

    public synchronized boolean remove(UUID ticketId) {
        Objects.requireNonNull(ticketId, "ticketId");
        return tickets.removeIf(ticket -> ticket.ticketId().equals(ticketId));
    }

    /** Finds and removes the oldest exact two-team composition, if available. */
    public synchronized Optional<QueueMatchProposal> tryMatch(ArenaFormat format, ArenaKitMode kitMode) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(kitMode, "kitMode");
        var candidates = tickets.stream()
                .filter(ticket -> ticket.format().equals(format) && ticket.kitMode() == kitMode)
                .toList();
        for (int firstCount = 1; firstCount <= candidates.size(); firstCount++) {
            var first = new ArrayList<QueueTicket>();
            if (!select(candidates, 0, firstCount, format.teamASize(), first)) continue;
            var used = new LinkedHashSet<UUID>();
            first.forEach(ticket -> used.add(ticket.partyId()));
            var remainder = candidates.stream().filter(ticket -> !used.contains(ticket.partyId())).toList();
            for (int secondCount = 1; secondCount <= remainder.size(); secondCount++) {
                var second = new ArrayList<QueueTicket>();
                if (!select(remainder, 0, secondCount, format.teamBSize(), second)) continue;
                var selected = new LinkedHashSet<QueueTicket>();
                selected.addAll(first);
                selected.addAll(second);
                tickets.removeAll(selected);
                return Optional.of(QueueMatchProposal.from(format, kitMode, first, second));
            }
        }
        return Optional.empty();
    }

    private static boolean select(List<QueueTicket> source, int index, int count, int players,
                                  List<QueueTicket> selected) {
        if (selected.size() == count) {
            return selected.stream().mapToInt(ticket -> ticket.partyRoster().players().size()).sum() == players;
        }
        if (source.size() - index < count - selected.size()) return false;
        for (int i = index; i < source.size(); i++) {
            selected.add(source.get(i));
            if (select(source, i + 1, count, players, selected)) return true;
            selected.remove(selected.size() - 1);
        }
        return false;
    }

    public synchronized List<QueueTicket> tickets() { return List.copyOf(tickets); }
}
