package com.ciaac.minecraft.minigames.arena;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Ephemeral, UUID-keyed party state used only to compose a Coliseum queue entry.
 *
 * <p>A party never grants permissions, teleports a player, or changes survival
 * state. Queue admission freezes a detached {@link TeamRoster}; later party
 * changes therefore cannot mutate a reserved match.</p>
 */
public final class ArenaPartyRegistry {
    private final Duration inviteLifetime;
    private final Map<UUID, MutableParty> parties = new LinkedHashMap<>();
    private final Map<UUID, UUID> partyByMember = new HashMap<>();
    private final Map<InviteKey, ArenaPartyInvite> invites = new HashMap<>();

    public ArenaPartyRegistry(Duration inviteLifetime) {
        this.inviteLifetime = Objects.requireNonNull(inviteLifetime, "inviteLifetime");
        if (inviteLifetime.isZero() || inviteLifetime.isNegative()
                || inviteLifetime.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("Party invitation lifetime must be between one tick and 15 minutes");
        }
    }

    public synchronized ArenaParty create(UUID leader, Instant now) {
        Objects.requireNonNull(leader, "leader");
        Objects.requireNonNull(now, "now");
        if (partyByMember.containsKey(leader)) {
            throw new IllegalStateException("PLAYER_ALREADY_IN_PARTY");
        }
        MutableParty party = new MutableParty(UUID.randomUUID(), leader, now);
        parties.put(party.id, party);
        partyByMember.put(leader, party.id);
        return party.view();
    }

    public synchronized ArenaPartyInvite invite(UUID leader, UUID target, Instant now) {
        Objects.requireNonNull(leader, "leader");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(now, "now");
        purgeExpired(now);
        MutableParty party = requirePartyOf(leader);
        requireLeader(party, leader);
        if (party.members.size() >= ArenaParty.MAX_MEMBERS) {
            throw new IllegalStateException("PARTY_FULL");
        }
        if (partyByMember.containsKey(target)) {
            throw new IllegalStateException("TARGET_ALREADY_IN_PARTY");
        }
        InviteKey key = new InviteKey(leader, target);
        ArenaPartyInvite invite = new ArenaPartyInvite(
                UUID.randomUUID(), party.id, leader, target, now, now.plus(inviteLifetime));
        invites.put(key, invite);
        return invite;
    }

    public synchronized ArenaParty accept(UUID target, UUID leader, Instant now) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(leader, "leader");
        Objects.requireNonNull(now, "now");
        purgeExpired(now);
        if (partyByMember.containsKey(target)) {
            throw new IllegalStateException("PLAYER_ALREADY_IN_PARTY");
        }
        ArenaPartyInvite invite = invites.remove(new InviteKey(leader, target));
        if (invite == null || !invite.validAt(now)) {
            throw new IllegalStateException("INVITE_UNAVAILABLE");
        }
        MutableParty party = parties.get(invite.partyId());
        if (party == null || !party.leader.equals(leader)) {
            throw new IllegalStateException("PARTY_UNAVAILABLE");
        }
        if (party.members.size() >= ArenaParty.MAX_MEMBERS) {
            throw new IllegalStateException("PARTY_FULL");
        }
        party.members.add(target);
        partyByMember.put(target, party.id);
        removeInvitesFor(target);
        return party.view();
    }

    /**
     * Leaves a party. When the leader leaves, leadership passes deterministically
     * to the oldest remaining member; an empty party is deleted.
     */
    public synchronized Optional<ArenaParty> leave(UUID member) {
        MutableParty party = requirePartyOf(Objects.requireNonNull(member, "member"));
        party.members.remove(member);
        partyByMember.remove(member);
        removeInvitesFor(member);
        if (party.members.isEmpty()) {
            removeParty(party);
            return Optional.empty();
        }
        if (party.leader.equals(member)) {
            party.leader = party.members.getFirst();
            invites.entrySet().removeIf(entry -> entry.getValue().partyId().equals(party.id));
        }
        return Optional.of(party.view());
    }

    public synchronized ArenaParty kick(UUID leader, UUID member) {
        MutableParty party = requirePartyOf(Objects.requireNonNull(leader, "leader"));
        requireLeader(party, leader);
        if (leader.equals(member) || !party.members.remove(Objects.requireNonNull(member, "member"))) {
            throw new IllegalArgumentException("PARTY_MEMBER_INVALID");
        }
        partyByMember.remove(member);
        removeInvitesFor(member);
        return party.view();
    }

    public synchronized void disband(UUID leader) {
        MutableParty party = requirePartyOf(Objects.requireNonNull(leader, "leader"));
        requireLeader(party, leader);
        removeParty(party);
    }

    public synchronized Optional<ArenaParty> findByMember(UUID member) {
        UUID partyId = partyByMember.get(Objects.requireNonNull(member, "member"));
        MutableParty party = partyId == null ? null : parties.get(partyId);
        return Optional.ofNullable(party == null ? null : party.view());
    }

    /** Returns a detached party roster, or a solo roster when no party exists. */
    public synchronized TeamRoster rosterFor(UUID player) {
        return findByMember(player).map(ArenaParty::roster).orElseGet(() -> TeamRoster.of(player));
    }

    public synchronized List<ArenaPartyInvite> invitationsFor(UUID target, Instant now) {
        purgeExpired(Objects.requireNonNull(now, "now"));
        return invites.values().stream()
                .filter(invite -> invite.target().equals(Objects.requireNonNull(target, "target")))
                .sorted(java.util.Comparator.comparing(ArenaPartyInvite::createdAt))
                .toList();
    }

    public synchronized void purgeExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        invites.entrySet().removeIf(entry -> !entry.getValue().validAt(now));
    }

    private MutableParty requirePartyOf(UUID player) {
        UUID partyId = partyByMember.get(player);
        MutableParty party = partyId == null ? null : parties.get(partyId);
        if (party == null) throw new IllegalStateException("PARTY_UNAVAILABLE");
        return party;
    }

    private static void requireLeader(MutableParty party, UUID player) {
        if (!party.leader.equals(player)) throw new IllegalStateException("PARTY_LEADER_REQUIRED");
    }

    private void removeParty(MutableParty party) {
        parties.remove(party.id);
        party.members.forEach(partyByMember::remove);
        invites.entrySet().removeIf(entry -> entry.getValue().partyId().equals(party.id));
    }

    private void removeInvitesFor(UUID player) {
        invites.entrySet().removeIf(entry -> entry.getValue().leader().equals(player)
                || entry.getValue().target().equals(player));
    }

    private record InviteKey(UUID leader, UUID target) {
        private InviteKey {
            Objects.requireNonNull(leader, "leader");
            Objects.requireNonNull(target, "target");
        }
    }

    private static final class MutableParty {
        private final UUID id;
        private UUID leader;
        private final List<UUID> members = new ArrayList<>();
        private final Instant createdAt;

        private MutableParty(UUID id, UUID leader, Instant createdAt) {
            this.id = id;
            this.leader = leader;
            this.createdAt = createdAt;
            members.add(leader);
        }

        private ArenaParty view() {
            return new ArenaParty(id, leader, members, createdAt);
        }
    }
}
